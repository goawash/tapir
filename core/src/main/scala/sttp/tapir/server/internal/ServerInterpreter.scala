package sttp.tapir.server.internal

import sttp.model.Headers
import sttp.monad.MonadError
import sttp.monad.syntax._
import sttp.tapir.internal.ParamsAsAny
import sttp.tapir.{DecodeResult, Endpoint, EndpointIO, EndpointInput, EndpointOutput, StreamBodyIO}
import sttp.tapir.model.{ServerRequest, ServerResponse}
import sttp.tapir.server.interceptor.{EndpointInterceptor, ValuedEndpointOutput}
import sttp.tapir.server.{ServerDefaults, ServerEndpoint}

class ServerInterpreter[R, F[_]: MonadError, WB, B, S](
    request: ServerRequest,
    requestBody: RequestBody[F, S],
    rawToResponseBody: ToResponseBody[WB, B, S],
    interceptors: List[EndpointInterceptor[F]]
) {
  def apply(ses: List[ServerEndpoint[_, _, _, R, F]]): F[Option[ServerResponse[WB, B]]] =
    ses match {
      case Nil => (None: Option[ServerResponse[WB, B]]).unit
      case se :: tail =>
        apply(se).flatMap {
          case None => apply(tail)
          case r    => r.unit
        }
    }

  def apply[I, E, O](se: ServerEndpoint[I, E, O, R, F]): F[Option[ServerResponse[WB, B]]] = {
    def valueToResponse(i: I): F[ServerResponse[WB, B]] = {
      se.logic(implicitly)(i)
        .map {
          case Right(result) => outputToResponse(ServerDefaults.StatusCodes.success, se.endpoint.output, result)
          case Left(err)     => outputToResponse(ServerDefaults.StatusCodes.error, se.endpoint.errorOutput, err)
        }
    }

    val decodedBasicInputs = DecodeBasicInputs(se.endpoint.input, request)

    decodeBody(decodedBasicInputs).flatMap {
      case values: DecodeBasicInputsResult.Values =>
        InputValue(se.endpoint.input, values) match {
          case InputValueResult.Value(params, _) =>
            callInterceptorsOnDecodeSuccess(interceptors, se.endpoint, params.asAny.asInstanceOf[I], valueToResponse).map(Some(_))
          case InputValueResult.Failure(input, failure) =>
            callInterceptorsOnDecodeFailure(interceptors, se.endpoint, input, failure)
        }
      case DecodeBasicInputsResult.Failure(input, failure) => callInterceptorsOnDecodeFailure(interceptors, se.endpoint, input, failure)
    }
  }

  private def callInterceptorsOnDecodeSuccess[I](
      is: List[EndpointInterceptor[F]],
      endpoint: Endpoint[I, _, _, _],
      i: I,
      callLogic: I => F[ServerResponse[WB, B]]
  ): F[ServerResponse[WB, B]] = is match {
    case Nil => callLogic(i)
    case interpreter :: tail =>
      interpreter.onDecodeSuccess(
        request,
        endpoint,
        i,
        {
          case None                                      => callInterceptorsOnDecodeSuccess(tail, endpoint, i, callLogic)
          case Some(ValuedEndpointOutput(output, value)) => outputToResponse(ServerDefaults.StatusCodes.success, output, value).unit
        }
      )
  }

  private def callInterceptorsOnDecodeFailure(
      is: List[EndpointInterceptor[F]],
      endpoint: Endpoint[_, _, _, _],
      failingInput: EndpointInput[_],
      failure: DecodeResult.Failure
  ): F[Option[ServerResponse[WB, B]]] = is match {
    case Nil => Option.empty[ServerResponse[WB, B]].unit
    case interpreter :: tail =>
      interpreter.onDecodeFailure(
        request,
        endpoint,
        failure,
        failingInput,
        {
          case None => callInterceptorsOnDecodeFailure(tail, endpoint, failingInput, failure)
          case Some(ValuedEndpointOutput(output, value)) =>
            (Some(outputToResponse(ServerDefaults.StatusCodes.error, output, value)): Option[ServerResponse[WB, B]]).unit
        }
      )
  }

  private def decodeBody(result: DecodeBasicInputsResult): F[DecodeBasicInputsResult] =
    result match {
      case values: DecodeBasicInputsResult.Values =>
        values.bodyInputWithIndex match {
          case Some((Left(bodyInput @ EndpointIO.Body(_, codec, _)), _)) =>
            requestBody.toRaw(bodyInput.bodyType).map { v =>
              codec.decode(v) match {
                case DecodeResult.Value(bodyV)     => values.setBodyInputValue(bodyV)
                case failure: DecodeResult.Failure => DecodeBasicInputsResult.Failure(bodyInput, failure): DecodeBasicInputsResult
              }
            }

          case Some((Right(bodyInput @ EndpointIO.StreamBodyWrapper(StreamBodyIO(_, codec, _, _))), _)) =>
            (codec.decode(requestBody.toStream()) match {
              case DecodeResult.Value(bodyV)     => values.setBodyInputValue(bodyV)
              case failure: DecodeResult.Failure => DecodeBasicInputsResult.Failure(bodyInput, failure): DecodeBasicInputsResult
            }).unit

          case None => (values: DecodeBasicInputsResult).unit
        }
      case failure: DecodeBasicInputsResult.Failure => (failure: DecodeBasicInputsResult).unit
    }

  private def outputToResponse[O](defaultStatusCode: sttp.model.StatusCode, output: EndpointOutput[O], v: O): ServerResponse[WB, B] = {
    val outputValues = new EncodeOutputs(rawToResponseBody).apply(output, ParamsAsAny(v), OutputValues.empty)
    val statusCode = outputValues.statusCode.getOrElse(defaultStatusCode)

    val headers = outputValues.headers
    outputValues.body match {
      case Some(Left(bodyFromHeaders)) => ServerResponse(statusCode, headers, Some(Right(bodyFromHeaders(Headers(headers)))))
      case Some(Right(pipeF))          => ServerResponse(statusCode, headers, Some(Left(pipeF)))
      case None                        => ServerResponse(statusCode, headers, None)
    }
  }
}
