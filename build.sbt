import build.Build

val scalajs = Build.root
val ir = Build.irProject
val irJS = Build.irProjectJS
val compiler = Build.compiler
val tools = Build.tools
val toolsJS = Build.toolsJS
val jsEnvs = Build.jsEnvs
val jsEnvsTestKit = Build.jsEnvsTestKit
val jsEnvsTestSuite = Build.jsEnvsTestSuite
val testAdapter = Build.testAdapter
val sbtPlugin = Build.plugin
val javalanglib = Build.javalanglib
val javalib = Build.javalib
val scalalib = Build.scalalib
val libraryAux = Build.libraryAux
val library = Build.library
val javalibEx = Build.javalibEx
val stubs = Build.stubs
val cli = Build.cli
val testInterface = Build.testInterface
val jUnitRuntime = Build.jUnitRuntime
val jUnitTestOutputsJS = Build.jUnitTestOutputsJS
val jUnitTestOutputsJVM = Build.jUnitTestOutputsJVM
val jUnitPlugin = Build.jUnitPlugin
val examples = Build.examples
val helloworld = Build.helloworld
val reversi = Build.reversi
val testingExample = Build.testingExample
val testSuite = Build.testSuite
val testSuiteJVM = Build.testSuiteJVM
val noIrCheckTest = Build.noIrCheckTest
val javalibExTestSuite = Build.javalibExTestSuite
val partest = Build.partest
val partestSuite = Build.partestSuite
val scalaTestSuite = Build.scalaTestSuite

inThisBuild(Build.thisBuildSettings)
