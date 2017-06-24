package cbt

import cbt._
import java.io._
import scala.xml._

trait ExportBuildInformation { self: BaseBuild =>
  def buildInfoXml: String =
    BuildInformationSerializer.serialize(BuildInformation.Project(self)).toString
}

object BuildInformation {
  case class Project(
    name: String,
    root: File,
    rootModule: Module,
    modules: Seq[Module],
    libraries: Seq[Library],
    cbtLibraries: Seq[Library],
    scalaCompilers: Seq[ScalaCompiler]
  )

  case class Module(
    name: String,
    root: File,
    scalaVersion: String,
    sourceDirs: Seq[File],
    target: File,
    binaryDependencies: Seq[BinaryDependency],
    moduleDependencies: Seq[ModuleDependency],
    classpath: Seq[File],
    parentBuild: Option[String],
    scalacOptions: Seq[String]
  )

  case class Library( name: String, jars: Seq[File] )

  case class BinaryDependency( name: String )

  case class ModuleDependency( name: String )

  case class ScalaCompiler( version: String, jars: Seq[File] )

  object Project {
    def apply(build: BaseBuild): Project =
     new BuildInformationExporter(build).exportBuildInformation

    class BuildInformationExporter(rootBuild: BaseBuild) {
      def exportBuildInformation: Project = {
        val moduleBuilds = transitiveBuilds(rootBuild)
        val libraries = moduleBuilds
          .flatMap(_.transitiveDependencies)
          .collect { case d: BoundMavenDependency => exportLibrary(d) }
          .distinct
        val cbtLibraries = convertCbtLibraries
        val rootModule = exportModule(rootBuild)
        val modules = moduleBuilds
          .map(exportModule)
          .distinct
        val scalaCompilers = modules
          .map(_.scalaVersion)
          .map(v => ScalaCompiler(v, resolveScalaCompiler(rootBuild, v)))

        Project(
          rootModule.name,
          rootModule.root,
          rootModule,
          modules,
          libraries,
          cbtLibraries,
          scalaCompilers
        )
      }

      private def convertCbtLibraries =
        transitiveBuilds(DirectoryDependency(rootBuild.context.cbtHome)(rootBuild.context).dependenciesArray.head.asInstanceOf[BaseBuild])
          .collect {
            case d: BoundMavenDependency => d.jar
            case d: PackageJars => d.jar.get
          }
          .map(exportLibrary)
          .distinct

      private def collectDependencies(dependencies: Seq[Dependency]): Seq[ModuleDependency] =
        dependencies
          .collect {
            case d: BaseBuild => Seq(ModuleDependency(moduleName(d)))
            case d: LazyDependency => collectDependencies(Seq(d.dependency))
          }
          .flatten

      private def exportModule(build: BaseBuild): Module = {
        val moduleDependencies = collectDependencies(build.dependencies)
        val mavenDependencies = build.dependencies
          .collect { case d: BoundMavenDependency => BinaryDependency(formatMavenDependency(d.mavenDependency)) }
        val classpath = build.dependencyClasspath.files
          .filter(_.isFile)
          .distinct
        val sourceDirs = {
          val s = build.sources
            .filter(_.exists)
            .map(handleSource)
            .filter(_.getName != "target") //Dirty hack for cbt's sources
            .distinct
          if (s.nonEmpty)
            s
          else
            Seq(build.projectDirectory)
        }

        Module(
          name = moduleName(build),
          root = build.projectDirectory,
          scalaVersion = build.scalaVersion,
          sourceDirs = sourceDirs,
          target = build.target,
          binaryDependencies = mavenDependencies,
          moduleDependencies = moduleDependencies,
          classpath = classpath,
          parentBuild = build.context.parentBuild.map(b => moduleName(b.asInstanceOf[BaseBuild])),
          scalacOptions = build.scalacOptions
        )
      }

      private def collectLazyBuilds(dependency: Dependency): Option[BaseBuild] =
        dependency match {
          case l: LazyDependency =>
            l.dependency match {
              case d: BaseBuild => Some(d)
              case d: LazyDependency => collectLazyBuilds(d.dependency)
              case _ => None
            }
          case d: BaseBuild => Some(d)
          case _ => None
        }


      private def transitiveBuilds(build: BaseBuild): Seq[BaseBuild] =
        (build +: build.transitiveDependencies)
          .collect {
            case d: BaseBuild => d +: collectParentBuilds(d).flatMap(transitiveBuilds)
            case d: LazyDependency =>
              collectLazyBuilds(d.dependency)
                .toSeq
                .flatMap(transitiveBuilds)
          }
          .flatten
          .distinct

      private def exportLibrary(mavenDependency: BoundMavenDependency) = {
        val name = formatMavenDependency(mavenDependency.mavenDependency)
        val jars = (mavenDependency +: mavenDependency.transitiveDependencies)
          .map(_.asInstanceOf[BoundMavenDependency].jar)
        Library(name, jars)
      }

      private def exportLibrary(file: File) =
        Library("CBT:" + file.getName.stripSuffix(".jar"), Seq(file))

      private def collectParentBuilds(build: BaseBuild): Seq[BaseBuild] =
        build.context.parentBuild
          .map(_.asInstanceOf[BaseBuild])
          .map(b => b +: collectParentBuilds(b))
          .toSeq
          .flatten

      private def resolveScalaCompiler(build: BaseBuild, scalaVersion: String) =
        build.Resolver(mavenCentral, sonatypeReleases).bindOne(
          MavenDependency("org.scala-lang", "scala-compiler", scalaVersion)
        ).classpath.files

      private def handleSource(source: File): File =
        if (source.isDirectory)
          source
        else
          source.getParentFile //Let's asume that for now


      private def formatMavenDependency(dependency: cbt.MavenDependency) =
        s"${dependency.groupId}:${dependency.artifactId}:${dependency.version}"

      private def moduleName(build: BaseBuild) =
        if (rootBuild.projectDirectory == build.projectDirectory)
          rootBuild.projectDirectory.getName
        else
          build.projectDirectory.getPath
            .drop(rootBuild.projectDirectory.getPath.length)
            .stripPrefix("/")
            .replace("/", "-")
    }
  }
}

object BuildInformationSerializer {
  def serialize(project: BuildInformation.Project): Node =
    <project name={project.name} root={project.root.toString} rootModule={project.rootModule.name}>
      <modules>
        {project.modules.map(serialize)}
      </modules>
      <libraries>
        {project.libraries.map(serialize)}
      </libraries>
      <cbtLibraries>
        {project.cbtLibraries.map(serialize)}
      </cbtLibraries>
      <scalaCompilers>
        {project.scalaCompilers.map(serialize)}
      </scalaCompilers>
    </project>

  private def serialize(module: BuildInformation.Module): Node =
    <module name={module.name} root={module.root.toString} target={module.target.toString} scalaVersion={module.scalaVersion}>
      <sourceDirs>
        {module.sourceDirs.map(d => <dir>{d}</dir>)}
      </sourceDirs>
      <scalacOptions>
        {module.scalacOptions.map(o => <option>{o}</option>)}
      </scalacOptions>
      <dependencies>
        {module.binaryDependencies.map(serialize)}
        {module.moduleDependencies.map(serialize)}
      </dependencies>
      <classpath>
        {module.classpath.map(c => <classpathItem>{c.toString}</classpathItem>)}
      </classpath>
      {module.parentBuild.map(p => <parentBuild>{p}</parentBuild>).getOrElse(NodeSeq.Empty)}
    </module>

  private def serialize(binaryDependency: BuildInformation.BinaryDependency): Node =
    <binaryDependency>{binaryDependency.name}</binaryDependency>

  private def serialize(library: BuildInformation.Library): Node =
    <library name={library.name}>
      {library.jars.map(j => <jar>{j}</jar>)}
    </library>

  private def serialize(compiler: BuildInformation.ScalaCompiler): Node =
    <compiler version={compiler.version}>
      {compiler.jars.map(j => <jar>{j}</jar>)}
    </compiler>

  private def serialize(moduleDependency: BuildInformation.ModuleDependency): Node =
    <moduleDependency>{moduleDependency.name}</moduleDependency>
}