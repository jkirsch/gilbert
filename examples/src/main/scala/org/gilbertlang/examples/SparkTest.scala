package org.gilbertlang.examples

import org.gilbertlang.language.Gilbert
import org.gilbertlang.runtime.withSpark
import org.apache.log4j.{Level, Logger}

object SparkTest {

  def main(args:Array[String]){
    val dop = 4
    val executable = Gilbert.compileRessource("nnmf.gb")
    val jarFiles = List("examples/target/examples-0.1-SNAPSHOT.jar",
      "runtimeMacros/target/runtimeMacros-0.1-SNAPSHOT.jar",
      "runtime/target/runtime-0.1-SNAPSHOT.jar",
      "/Users/till/.m2/repository/eu/stratosphere/stratosphere-core/0.5-SNAPSHOT/stratosphere-core-0.5-SNAPSHOT.jar")
    withSpark(executable).remote("spark://node1:7077",checkpointDir= "", iterationsUntilCheckpoint = 0,
      appName= "Gilbert",parallelism= dop, outputPath= None,jars = jarFiles)
  }
}