/* Scala.js compiler
 * Copyright 2013 LAMP/EPFL
 * @author  Sébastien Doeraene
 */

package org.scalajs.core.compiler

import scala.tools.nsc._

import scala.collection.mutable

import org.scalajs.core.ir.Trees.JSNativeLoadSpec

/** Additions to Global meaningful for the JavaScript backend
 *
 *  @author Sébastien Doeraene
 */
trait JSGlobalAddons extends JSDefinitions
                        with Compat210Component {
  val global: Global

  import global._
  import jsDefinitions._
  import definitions._

  /** JavaScript primitives, used in jscode */
  object jsPrimitives extends JSPrimitives {
    val global: JSGlobalAddons.this.global.type = JSGlobalAddons.this.global
    val jsAddons: ThisJSGlobalAddons =
      JSGlobalAddons.this.asInstanceOf[ThisJSGlobalAddons]
  }

  sealed abstract class ExportDestination

  object ExportDestination {
    /** Export in the "normal" way: as an instance member, or at the top-level
     *  for naturally top-level things (classes and modules).
     */
    case object Normal extends ExportDestination

    /** Export at the top-level. */
    case object TopLevel extends ExportDestination

    /** Export as a static member of the companion class. */
    case object Static extends ExportDestination
  }

  /** Extracts the super type of a `Template`, with type parameters reinvented
   *  so that the type is well-formed outside of the `Template`, i.e., at the
   *  same level where the corresponding `ImplDef` is defined.
   */
  def extractSuperTpeFromImpl(impl: Template): Type =
    reinventTypeParams(impl.parents.head.tpe)

  /** Reinvents all the type parameters of a `TypeRef`.
   *
   *  This is done by existentially quantifying over all type parameters of
   *  the class type referenced by the `TypeRef`.
   *
   *  As a simple example, given the definition
   *  {{{
   *  class C[A, B <: AnyVal]
   *  }}}
   *  this transforms
   *  {{{
   *  path.C[A, Int]
   *  }}}
   *  into
   *  {{{
   *  path.C[_, _ <: AnyVal]
   *  }}}
   *
   *  As a complex example, given the definition
   *  {{{
   *  class D[A, B <: List[Seq[A]]]
   *  }}}
   *  this method transforms
   *  {{{
   *  path.D[?0, ?1] forSome { type ?0; type ?1 <: List[Seq[?0]] }
   *  }}}
   */
  private def reinventTypeParams(tp: Type): Type = {
    tp match {
      case TypeRef(pre, sym, _) if sym.isClass && sym.typeParams.nonEmpty =>
        val eparams = typeParamsToExistentials(sym)
        existentialAbstraction(eparams, typeRef(pre, sym, eparams.map(_.tpe)))
      case _ =>
        tp
    }
  }

  /** global javascript interop related helpers */
  object jsInterop {
    import scala.reflect.NameTransformer
    import scala.reflect.internal.Flags

    /** Symbols of constructors and modules that are to be exported */
    private val exportedSymbols =
      mutable.Map.empty[Symbol, List[ExportInfo]]

    /** JS native load specs of the symbols in the current compilation run. */
    private val jsNativeLoadSpecs =
      mutable.Map.empty[Symbol, JSNativeLoadSpec]

    private val exportPrefix = "$js$exported$"
    private val methodExportPrefix = exportPrefix + "meth$"
    private val propExportPrefix = exportPrefix + "prop$"

    trait ExportInfo {
      val jsName: String
      val pos: Position
      val destination: ExportDestination
    }

    sealed abstract class JSName {
      def displayName: String
    }

    object JSName {
      // Not final because it causes annoying compile warnings
      case class Literal(name: String) extends JSName {
        def displayName: String = name
      }

      // Not final because it causes annoying compile warnings
      case class Computed(sym: Symbol) extends JSName {
        def displayName: String = sym.fullName
      }
    }

    def clearGlobalState(): Unit = {
      exportedSymbols.clear()
      jsNativeLoadSpecs.clear()
    }

    def registerForExport(sym: Symbol, infos: List[ExportInfo]): Unit = {
      assert(!exportedSymbols.contains(sym),
          "Same symbol exported twice: " + sym)
      exportedSymbols.put(sym, infos)
    }

    def registeredExportsOf(sym: Symbol): List[ExportInfo] = {
      exportedSymbols.getOrElse(sym, Nil)
    }

    /** creates a name for an export specification */
    def scalaExportName(jsName: String, isProp: Boolean): TermName = {
      val pref = if (isProp) propExportPrefix else methodExportPrefix
      val encname = NameTransformer.encode(jsName)
      newTermName(pref + encname)
    }

    /** checks if the given symbol is a JSExport */
    def isExport(sym: Symbol): Boolean =
      sym.unexpandedName.startsWith(exportPrefix) &&
      !sym.hasFlag(Flags.DEFAULTPARAM)

    /** retrieves the originally assigned jsName of this export and whether it
     *  is a property
     */
    def jsExportInfo(name: Name): (String, Boolean) = {
      def dropPrefix(prefix: String) ={
        if (name.startsWith(prefix)) {
          // We can't decode right away due to $ separators
          val enc = name.encoded.substring(prefix.length)
          Some(NameTransformer.decode(enc))
        } else None
      }

      dropPrefix(methodExportPrefix).map((_,false)).orElse {
        dropPrefix(propExportPrefix).map((_,true))
      }.getOrElse {
        throw new IllegalArgumentException(
            "non-exported name passed to jsExportInfo")
      }
    }

    def jsclassAccessorFor(clazz: Symbol): Symbol =
      clazz.owner.info.member(clazz.name.append("$jsclass").toTermName)

    def isJSProperty(sym: Symbol): Boolean = isJSGetter(sym) || isJSSetter(sym)

    @inline private def enteringUncurryIfAtPhaseAfter[A](op: => A): A = {
      if (currentRun.uncurryPhase != NoPhase &&
          isAtPhaseAfter(currentRun.uncurryPhase)) {
        enteringPhase(currentRun.uncurryPhase)(op)
      } else {
        op
      }
    }

    /** has this symbol to be translated into a JS getter (both directions)? */
    def isJSGetter(sym: Symbol): Boolean = {
      /* We only get here when `sym.isMethod`, thus `sym.isModule` implies that
       * `sym` is the module's accessor. In 2.12, module accessors are synthesized
       * after uncurry, thus their first info is a MethodType at phase fields.
       */
      sym.isModule || (sym.tpe.params.isEmpty && enteringUncurryIfAtPhaseAfter {
        sym.tpe match {
          case _: NullaryMethodType              => true
          case PolyType(_, _: NullaryMethodType) => true
          case _                                 => false
        }
      })
    }

    /** has this symbol to be translated into a JS setter (both directions)? */
    def isJSSetter(sym: Symbol): Boolean =
      nme.isSetterName(sym.name) && sym.isMethod && !sym.isConstructor

    /** Is this field symbol a static field at the IR level? */
    def isFieldStatic(sym: Symbol): Boolean = {
      sym.owner.isModuleClass && // usually false, avoids a lookup in the map
      registeredExportsOf(sym).nonEmpty
    }

    /** The export info of a static field.
     *
     *  Requires `isFieldStatic(sym)`.
     *
     *  The result is non-empty. If it contains an `ExportInfo` with
     *  `isStatic = true`, then it is the only element in the list. Otherwise,
     *  all elements have `isTopLevel = true`.
     */
    def staticFieldInfoOf(sym: Symbol): List[ExportInfo] =
      registeredExportsOf(sym)

    /** has this symbol to be translated into a JS bracket access (JS to Scala) */
    def isJSBracketAccess(sym: Symbol): Boolean =
      sym.hasAnnotation(JSBracketAccessAnnotation)

    /** has this symbol to be translated into a JS bracket call (JS to Scala) */
    def isJSBracketCall(sym: Symbol): Boolean =
      sym.hasAnnotation(JSBracketCallAnnotation)

    /** Gets the unqualified JS name of a symbol.
     *
     *  If it is not explicitly specified with an `@JSName` annotation, the
     *  JS name is inferred from the Scala name.
     */
    def jsNameOf(sym: Symbol): JSName = {
      sym.getAnnotation(JSNameAnnotation).fold[JSName] {
        JSName.Literal(defaultJSNameOf(sym))
      } { annotation =>
        annotation.args.head match {
          case Literal(Constant(name: String)) => JSName.Literal(name)
          case tree                            => JSName.Computed(tree.symbol)
        }
      }
    }

    def defaultJSNameOf(sym: Symbol): String = {
      val base = sym.unexpandedName.decoded.stripSuffix("_=")
      if (!sym.isMethod) base.stripSuffix(" ")
      else base
    }

    /** Stores the JS native load spec of a symbol for the current compilation
     *  run.
     */
    def storeJSNativeLoadSpec(sym: Symbol, spec: JSNativeLoadSpec): Unit = {
      assert(sym.isClass,
          s"storeJSNativeLoadSpec called for non-class symbol $sym")

      jsNativeLoadSpecs(sym) = spec
    }

    /** Gets the JS native load spec of a symbol in the current compilation run.
     */
    def jsNativeLoadSpecOf(sym: Symbol): JSNativeLoadSpec = {
      assert(sym.isClass,
          s"jsNativeLoadSpecOf called for non-class symbol $sym")

      jsNativeLoadSpecs(sym)
    }

    /** Gets the JS native load spec of a symbol in the current compilation run,
     *  if it has one.
     */
    def jsNativeLoadSpecOfOption(sym: Symbol): Option[JSNativeLoadSpec] = {
      assert(sym.isClass,
          s"jsNativeLoadSpecOfOption called for non-class symbol $sym")

      jsNativeLoadSpecs.get(sym)
    }

  }

}
