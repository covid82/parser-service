package org.parseq.parserservice

import cats.Parallel
import cats.data.{Kleisli, OptionT}
import cats.effect.{Clock, ConcurrentEffect, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.syntax.functor._
import io.jaegertracing.Configuration.{ReporterConfiguration, SamplerConfiguration}
import io.prometheus.client.CollectorRegistry
import natchez.Tags.http
import natchez.jaeger.Jaeger
import natchez.{EntryPoint, Kernel, Span}
import org.http4s
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.metrics.prometheus.{Prometheus, PrometheusExportService}
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Metrics
import org.http4s.server.{Router, Server}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.dsl.Http4sClientDsl
import org.parseq.parserservice.expression.Expr

import scala.concurrent.ExecutionContext.global
import scala.language.higherKinds

object Main extends IOApp {

  def spannedClient[F[_] : Sync : Span : Client]: Client[F] = {
    val span = implicitly[Span[F]]
    val client =implicitly[Client[F]]
    Client[F](request =>
      for {
        s <- span.span(s"http4s-request")
        //        _      <- Resource.liftF(s.put(span.kind("client")))
        _ <- Resource.liftF(s.put(http.method(request.method.name)))
        _ <- Resource.liftF(s.put(http.url(request.uri.renderString)))
        kernel <- Resource.liftF(s.kernel)
        c <- client.run(request.transformHeaders(headers => headers ++ http4s.Headers(kernel.toHeaders.toList.map(b => Header(b._1, b._2)))))
        _ <- Resource.liftF(s.put(http.status_code(c.status.code.toString)))
      } yield c
    )
  }

  def httpGet[F[_] : ConcurrentEffect, A, B](url: String)(f: A => F[B])(implicit c: Client[F], s: Span[F], d: EntityDecoder[F, A]): F[B] = {
    import cats.syntax.flatMap._
    spannedClient.expect[A](url).flatMap(f)
  }

  def httpGetString[F[_] : ConcurrentEffect, A](url: String)(implicit c: Client[F], s: Span[F], d: EntityDecoder[F, A]): F[String] = {
    spannedClient.expect[String](url)
  }

  val baseUrl = "http://math-service:8082/api"

  def rand[F[_] : ConcurrentEffect : Span : Client](x: Int)(implicit e: EntityDecoder[F, String]): F[String] =
    httpGetString(s"$baseUrl/random/$x")

  def sum[F[_] : ConcurrentEffect : Span : Client](x: Int, y: Int)(implicit e: EntityDecoder[F, String]): F[String] =
    httpGetString(s"$baseUrl/sum/$x/$y")

  def calculate[F[_] : ConcurrentEffect : Span : Client](expr: Expr)(implicit e: EntityDecoder[F, String]): F[String] = {
    object dsl extends Http4sClientDsl[F]
    import dsl._
    import org.http4s.Method._
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityCodec._
    spannedClient.expect(POST(expr.asJson, uri"http://math-service:8082/api/calculate"))(implicitly[EntityDecoder[F, String]])
  }

  def api[F[_] : ConcurrentEffect : Parallel](ep: EntryPoint[F], client: Client[F]): Kleisli[OptionT[F, *], Request[F], Response[F]] = {
    object dsl extends Http4sDsl[F]
    import dsl._
    implicit val c: Client[F] = client

    HttpRoutes.of[F] {

      case GET -> Root / "health" => Ok("Ok")

      case req@GET -> Root / "parse" =>
        ep.root(s"parse").use { implicit span =>
          import io.circe.generic.auto._
          import org.http4s.circe.CirceEntityCodec._
          import cats.syntax.flatMap._
          import expression._
          for {
            expr <- req.as[Expr]
            result <- calculate(expr)
            resp <- Ok(result)
          } yield resp
        }

      case GET -> Root / "seq" / IntVar(x) / IntVar(y) => ep.root(s"seq($x/$y)").use { implicit span =>
        import cats.syntax.apply._
        import cats.syntax.flatMap._
        (rand(x), rand(y)).tupled.flatMap { case (a, b) =>
          sum(a.toInt, b.toInt)
        }.flatMap(Ok(_))
      }

      case GET -> Root / "par" / IntVar(x) / IntVar(y) => ep.root(s"par($x/$y)").use { implicit span =>
        import cats.syntax.parallel._
        import cats.syntax.flatMap._
        (rand(x), rand(y)).parTupled.flatMap { case (a, b) =>
          sum(a.toInt, b.toInt)
        }.flatMap(Ok(_))
      }
    }
  }

  def span[F[_]](name: String)(req: Request[F], ep: EntryPoint[F]): Resource[F, Span[F]] =
    ep.continueOrElseRoot(name, Kernel(req.headers.toList.map(h => h.name.value -> h.value).toMap))

  def server[F[_] : ConcurrentEffect : Timer](routes: HttpApp[F]): Resource[F, Server[F]] = BlazeServerBuilder[F]
    .withHttpApp(routes)
    .bindHttp(8080, "0.0.0.0")
    .resource

  def client[F[_] : ConcurrentEffect]: Resource[F, Client[F]] = {
    BlazeClientBuilder[F](global).resource
  }

  def entryPoint[F[_] : Sync]: Resource[F, EntryPoint[F]] = Jaeger.entryPoint[F]("parser-service") { conf =>
    Sync[F].delay(conf
      .withSampler(SamplerConfiguration.fromEnv())
      .withReporter(ReporterConfiguration.fromEnv())
      .getTracer
    )
  }

  implicit val clock: Clock[IO] = Clock.create[IO]

  def meteredRoutes[F[_] : ConcurrentEffect : Clock](routes: HttpRoutes[F], registry: CollectorRegistry): Resource[F, HttpRoutes[F]] =
    Prometheus.metricsOps[F](registry, "http4s_app").map(ops => Metrics[F](ops)(routes))

  /**
   * http://127.0.0.1:28080/api/hello/X
   * http://127.0.0.1:28080/metrics
   */
  override def run(args: List[String]): IO[ExitCode] = {
    for {
      entryPoint <- entryPoint[IO]
      prometheus <- PrometheusExportService.build[IO]
      client <- client[IO]
      apiRoutes <- meteredRoutes[IO](api(entryPoint, client), prometheus.collectorRegistry)
      server <- server[IO](Router(
        "/" -> prometheus.routes,
        "/api" -> apiRoutes
      ).orNotFound)
    } yield server
  }.use(_ => IO.never) as ExitCode.Success
}