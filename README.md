# zio-wrk

 Performance tool with TLS, simular to wrk.  ( initial pilot implementation )

 sbt "run http://localhost:8080/health 64"   run with 64 zio fibers.
 
 sbt "run https://localhost:8443/health 64"  ( make sure you have keystore.jks - or edit zio-wrk.scala )
