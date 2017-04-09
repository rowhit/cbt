package cbt_examples_build.cross_rewrite
import cbt._
import java.io.File
import scala.meta._
import scalafix.util._
import scalafix.util.TreePatch._
import scalafix.util.TokenPatch._


/*
  object DayDreaming {
    val imaginaryBaseBuild = BaseBuild(
      context = Context,
      defaultScalaVersion = "2.12.1"
    )
      .map(addPlugin(Scalameta))
      .map(addPlugin(Scalatest)) //I would expect to normally use the default test directory config
      .map(addPlugin(CrossBuild))
      .map(addPlugin(CrossRewriter))
      .map { build => //I would expect that here I would have Build with MetaBuild with ScalaTestBuild with CrossBuild with CrossRewriter
        build.copy(
          crossBuilds = Seq( //This would be a sequence of build transforms. If I had many I would create a function to build a matrix of transforms
            setVersion("2.11.8") andThen
              addDependency(MavenDependency("org.scalaz", "scalaz-core", "7.2.10")) andThen
              transformSources( //transformSources would be in CrossRewriter plugin and would apply rewrites to all sources
                  AddGlobalImport(importer"scalaz._"),
                  Replace(Symbol("_root_.scala.package.Either."), q"\/"),
                  Replace(Symbol("_root_.scala.util.Right."), q"\/-"),
                  RemoveGlobalImport(importer"cats.implicits._")
                ),
            setVersion("2.11.8") andThen
              addDependency(MavenDependency("org.typelevel", "cats", "0.9.0")) andThen
              transformSources(AddGlobalImport(importer"cats.implicits._"))
          )
        )
      }

    def setVersion(version: String): Build => Build = _.copy(scalaVersion = version)
    def addDependency(dep: MavenDependency): Build => Build = _.copy(dependencies = dependencies :+ dep)
    def removeDependency(dep: MavenDependency): Build => Build = _.copy(dependencies = dependencies :- dep)

    //Over time I would hope to build up a community libraryof rewrites so many of the transforms would be pulled from
    //a library instead of declared in the build
  }
*/

class Build(val context: Context) extends BaseBuild with Scalameta { outer =>
  override def defaultScalaVersion = "2.12.1"

  override def test: Dependency = {
    new BasicBuild(context) with ScalaTest {
      def apply = run
      override def dependencies = outer +: super.dependencies
      override def defaultScalaVersion = outer.scalaVersion
      override def sources = Seq(context.workingDirectory / "test")
      override def projectDirectory = {
        val d = outer.projectDirectory / "test"
        d.mkdirs
        d
      }
    }
  }

//  case class CrossRewrite(name: String, patches: Seq[Patch], scalaVersion: Option[ScalaVersion], lib: Option[String])
//
//  case class AddDependency(dep: MavenDependency)
//  case class RemoveDependency(dep: MavenDependency)

  def versions = Seq[(String, Seq[Patch])](
//    scalaVersion -> Seq(),
    "2.11.8" -> Seq(
      RemoveGlobalImport(
        importer"scala.concurrent.Future"
      ),
      AddGlobalImport(
        importer"scala.util.Try"
      )
    )
  )
  def libs = Seq[(String, MavenDependency, Seq[Patch])](
    (
      "scalaz",
      MavenDependency( "org.scalaz", "scalaz-core", "7.2.10" ),
      Seq(
        AddGlobalImport(importer"scalaz._"),
        Replace(Symbol("_root_.scala.package.Either."), q"\/"),
        Replace(Symbol("_root_.scala.util.Right."), q"\/-"),
        RemoveGlobalImport(importer"cats.implicits._")
      )
    ),
    (
      "cats",
      MavenDependency( "org.typelevel", "cats", "0.9.0" ),
      Seq(
        AddGlobalImport(importer"cats.implicits._")
      )
    )
  )

  def cross = versions.flatMap{ case ( v, version_rewrites ) =>
    libs.map{
      case ( label, dep, lib_rewrites ) =>
        val d = outer.target / "rewrites" / label ++ "-" ++ v
        d.mkdirs
        new Build(context) with Scalafix with PackageJars{
          override def groupId = "org.cvogt"
          override def artifactId = "cbt-examples-cross-rewrite-" + label
          override def version = "0.1"
          override def defaultScalaVersion = v
          override def dependencies =
            super.dependencies ++ Resolver(mavenCentral).bind(
              // hack because using ScalaDependency in the outer build binds it
              // to THAT builds initial scalaVersion, which we are overriding
              // here, but we are looping over libs outside of that, so
              // the override doesn't affect it
              // So we use MavenDependency instead and append the id here.
              dep.copy(artifactId = dep.artifactId + "_" + scalaMajorVersion)
            )
          override def projectDirectory = d
          override def scaladoc = None
          override def sources = {
            val fromTo = lib.autoRelative( outer.sources ).collect{
              case (location, relative) if location.isFile
              => location -> projectDirectory / "src" / relative
            }

            val to = fromTo.map(_._2)
            assert( ( to diff to.distinct ).isEmpty )

            Scalafix.apply(lib).config(
              outer.classpath,
              files = fromTo,
              patches = lib_rewrites ++ version_rewrites,
              allowEmpty = true
            ).apply

            to
          }

          override def test: Dependency = {
            val testDirectory = projectDirectory / "test"
            new BasicBuild( context.copy(workingDirectory = testDirectory) ) with ScalaTest {
//              def apply = run

              override def dependencies = outer +: super.dependencies

              override def defaultScalaVersion = v

              override def sources = Seq(context.workingDirectory / "test")

              override def projectDirectory = {
                val d = outer.projectDirectory / "test"
                d.mkdirs
                d
              }
            }
          }

//          with
//          {
//            override def sources
//
//            =
//            {
//              val fromTo = lib.autoRelative(outer.sources).collect {
//                case (location, relative) if location.isFile
//                => location -> projectDirectory / "src" / relative
//              }
//
//              val to = fromTo.map(_._2)
//              assert((to diff to.distinct).isEmpty)
//
//              Scalafix.apply(lib).config(
//                outer.classpath,
//                files = fromTo,
//                patches = lib_rewrites ++ version_rewrites,
//                allowEmpty = true
//              ).apply
//
//              to
//            }
//          }

        }
    }
  }
}
