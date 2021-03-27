package ollls.ziowrk

import java.util.concurrent.TimeUnit
import zio.App
import zio.ZManaged
import zio.ZIO
import zio.ZEnv
import zio.Chunk
import zio.Semaphore
import zio.Ref
import zio.duration.Duration
import zio.json._

import zio.Runtime.Managed
import zio.Promise
import zio.ZEnv

import zhttp.clients.HttpConnection
import zhttp.clients.ClientRequest
import zhttp.Method.GET

import zhttp.clients.ResPool
import zhttp.MyLogging
import zhttp.MyLogging.MyLogging
import zhttp.LogLevel._
import zhttp.LogLevel
import zhttp.MyLogging.MyLogging
import zhttp.clients.ResPool.ResPool

import zio.blocking.effectBlocking

import java.util.concurrent.atomic.AtomicInteger
import zio.clock.Clock

object MyApp extends zio.App {

  var start = false

  def rstart(
      path: String, 
      s: Semaphore,
      p: Promise[Exception, Boolean],
      ok_cntr: AtomicInteger,
      err_cntr: AtomicInteger,
      fibNum: Int
  ) = {
    val http_remote = ZManaged.make(ResPool.acquire[HttpConnection])(c => {
      ResPool
        .release[HttpConnection](c)
        .catchAll(_ => { ZIO.succeed(println("println")) })
    })
    for {

      _ <- ZIO.unit
      process = (for {
        x <- http_remote.use(c =>
          (for {
            response <- c.send(ClientRequest(GET, path ))
            _ <- ZIO(ok_cntr.getAndIncrement).when(response.code.isSuccess)
            _ <- ZIO(err_cntr.getAndIncrement).when(!response.code.isSuccess)

          } yield ())
        )

      } yield ())
        .catchSome { case e: zhttp.clients.HttpConnectionError =>
          ZIO(println(e.toString)) *> ZIO(err_cntr.incrementAndGet)
        }
        .catchAll(e => MyLogging.error("console", "!" + e.toString))

      top_process = p.await *> ZIO(java.time.Instant.now().toEpochMilli)
        .flatMap { st =>
          process.repeatWhile(_ =>
            (java.time.Instant.now().toEpochMilli - st < 10000)
          )
        }

      last_fiber <- s.withPermit(top_process.fork).repeatN(fibNum)

    } yield (last_fiber)
  }

  ///////////////////////////////
  def run(args: List[String]) = {
 
    val URLSTR : String = if (args.length > 0) args(0) else null

    if ( URLSTR == null ) System.exit( -1 )

    val url = new java.net.URL( URLSTR )

    val path = url.getPath()

    val nfibers = if (args.length > 1) args(1).toInt else 2

    //Layers
    val logger_L = MyLogging.make(
      ("console" -> LogLevel.Off),
      ("client" -> LogLevel.Off)
    )

    val http_con_pool = ResPool.makeM[HttpConnection](
      timeToLiveMs = 10000,
      () => {
        HttpConnection
           .connect( url.toString(), tlsBlindTrust = true ) //if self signed
          //.connect( url.toString() ) // if remote signed with proper/approved CA
          //.connect( url.toString(), tlsBlindTrust = false, "keystore.jks" , "password") //for non tls - blind trust won't be used
      }, { c => c.close }
    )

    (for {

      _ <- ZIO( println( "\nTesting:    GET " +  url.toString() ) )
 
      _ <- ZIO( println( "ZIO fibers: " +  nfibers) )
      _ <- ZIO( println( "Interval:   10 sec") )

      sem <- zio.Semaphore.make(permits = nfibers )

      prom <- zio.Promise.make[Exception, Boolean]

      prom2 <- zio.Promise.make[Exception, Boolean]

      prom3 <- zio.Promise.make[Exception, Boolean]

      val ok_cntr = new java.util.concurrent.atomic.AtomicInteger(0)
      val err_cntr = new java.util.concurrent.atomic.AtomicInteger(0)

      val ok_cntr2 = new java.util.concurrent.atomic.AtomicInteger(0)
      val err_cntr2 = new java.util.concurrent.atomic.AtomicInteger(0)

      val ok_cntr3 = new java.util.concurrent.atomic.AtomicInteger(0)
      val err_cntr3 = new java.util.concurrent.atomic.AtomicInteger(0)

      /////////////////////////////////////////////////////////////////////////////////////
      last_fiber <- rstart(path, sem, prom, ok_cntr, err_cntr, nfibers )
      _ <- ZIO(println("\nWarm-up, Run #0"))
      time1 <- ZIO
        .accessM[Clock](cl => cl.get.currentDateTime)
        .map(c => c.toInstant().toEpochMilli())
      _ <- prom.succeed(true)
      _ <- last_fiber.await
      //make sure all fibers are completed
        _ <- sem.withPermits( nfibers )(ZIO.unit)
      _ <- ZIO(
        println(
          "Transactions in 10 s (success/errors) = " + ok_cntr.get + " / " + err_cntr.get
        )
      )
      time2 <- ZIO.accessM[Clock](cl => cl.get.currentDateTime).map(c => c.toInstant().toEpochMilli())
      _ <- ZIO(println("Finihed in " + (time2 - time1) + " ms"))

      /////////////////////////////////////////////////////////////////////////////////////////
      last_fiber2 <- rstart(path, sem, prom2, ok_cntr2, err_cntr2, nfibers )
      _ <- ZIO(println("\nRun #1"))
      time1 <- ZIO
        .accessM[Clock](cl => cl.get.currentDateTime)
        .map(c => c.toInstant().toEpochMilli())
      _ <- prom2.succeed(true)
      _ <- last_fiber2.await
      _ <- sem.withPermits(nfibers)(ZIO.unit)
      _ <- ZIO(
        println(
          "Transactions in 10 s (success/errors) = " + ok_cntr2.get + " / " + err_cntr2.get
        )
      )
      time2 <- ZIO.accessM[Clock](cl => cl.get.currentDateTime).map(c => c.toInstant().toEpochMilli())
      _ <- ZIO(println("Finihed in " + (time2 - time1) + " ms"))
      ////////////////////////////////////////////////////////////////////////////////////////

      last_fiber3 <- rstart(path, sem, prom3, ok_cntr3, err_cntr3, nfibers )
      _ <- ZIO(println("\nRun #2"))
      time1 <- ZIO.accessM[Clock](cl => cl.get.currentDateTime).map(c => c.toInstant().toEpochMilli())
      _ <- prom3.succeed(true)
      _ <- last_fiber3.await
       _ <- sem.withPermits( nfibers )(ZIO.unit)
      _ <- ZIO(
        println(
          "Transactions in 10 s (success/errors) = " + ok_cntr3.get + " / " + err_cntr3.get
        )
      )
      time2 <- ZIO.accessM[Clock](cl => cl.get.currentDateTime).map(c => c.toInstant().toEpochMilli())
      _ <- ZIO(println("Finihed in " + (time2 - time1) + " ms"))


      //_ <- ZIO.sleep( Duration.Infinity )

    } yield ())
      .catchAll(e => { MyLogging.error("console", e.toString) })
      .provideSomeLayer[ZEnv with MyLogging](http_con_pool)
      .provideSomeLayer[ZEnv](logger_L)
      .exitCode

  }
}
