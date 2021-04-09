package sttp.tapir.server.interceptor.metrics

import sttp.monad.MonadError
import sttp.monad.syntax._
import sttp.tapir.metrics.Metric
import sttp.tapir.model.{ServerRequest, ServerResponse}
import sttp.tapir.server.interceptor.{EndpointInterceptor, ValuedEndpointOutput}
import sttp.tapir.{DecodeResult, Endpoint, EndpointInput}

class MetricsInterceptor[F[_], B](metrics: List[Metric[F, _]], ignoreEndpoints: Seq[Endpoint[_, _, _, _]])(implicit monad: MonadError[F])
    extends EndpointInterceptor[F, B] {

  override def onDecodeSuccess[I](
      request: ServerRequest,
      endpoint: Endpoint[I, _, _, _],
      i: I,
      next: Option[ValuedEndpointOutput[_]] => F[ServerResponse[B]]
  )(implicit monad: MonadError[F]): F[ServerResponse[B]] = {
    if (ignoreEndpoints.contains(endpoint)) next(None)
    else {
      for {
        _ <- collectMetrics { case Metric(m, Some(onRequest), _, _) => onRequest(request, m) }
        response <- next(None)
        _ <- collectMetrics { case Metric(m, _, Some(onResponse), _) => onResponse(request, response, m) }
      } yield response
    }
  }

  /** Collects metrics when request matches `Endpoint`. First collects `onDecodeFailure` metric,
    * then, if there's some `ServerResponse` collects `onResponse` as well as `onRequest` metric which was not collected in `onDecodeSuccess` stage.
    */
  override def onDecodeFailure(
      request: ServerRequest,
      endpoint: Endpoint[_, _, _, _],
      failure: DecodeResult.Failure,
      failingInput: EndpointInput[_],
      next: Option[ValuedEndpointOutput[_]] => F[Option[ServerResponse[B]]]
  )(implicit monad: MonadError[F]): F[Option[ServerResponse[B]]] = {
    if (ignoreEndpoints.contains(endpoint)) next(None)
    else {
      failure match {
        case _: DecodeResult.Mismatch => next(None)
        case _ =>
          for {
            _ <- collectMetrics { case Metric(m, _, _, Some(onDecodeFailure)) => onDecodeFailure(request, m) }
            r <- next(None)
            _ <- r match {
              case Some(response) =>
                for {
                  _ <- collectMetrics { case Metric(m, Some(onRequest), _, _) => onRequest(request, m) }
                  _ <- collectMetrics { case Metric(m, _, Some(onResponse), _) => onResponse(request, response, m) }
                } yield ()
              case None => monad.unit(r)
            }
          } yield r
      }
    }
  }

  private def collectMetrics(pf: PartialFunction[Metric[F, _], F[Unit]]): F[Unit] = {
    def collect(metrics: List[Metric[F, _]]): F[Unit] = {
      metrics match {
        case Nil                            => monad.unit(())
        case m :: tail if pf.isDefinedAt(m) => pf(m).flatMap(_ => collect(tail))
        case _ :: tail                      => collect(tail)
      }
    }
    collect(metrics)
  }

}
