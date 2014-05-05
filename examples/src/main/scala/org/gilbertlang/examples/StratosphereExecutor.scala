package org.gilbertlang.examples

import org.gilbertlang.runtime.{local, withStratosphere}
import org.gilbertlang.language.Gilbert
import eu.stratosphere.client.LocalExecutor

object StratosphereExecutor {

  def main(args: Array[String]) {
    val executable = Gilbert.compileRessource("test.gb")

    withStratosphere(executable).local(4)
  }
}