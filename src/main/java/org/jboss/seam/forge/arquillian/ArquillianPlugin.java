package org.jboss.seam.forge.arquillian;

import org.jboss.forge.parser.JavaParser;
import org.jboss.forge.parser.java.JavaClass;
import org.jboss.forge.parser.java.JavaSource;
import org.jboss.forge.project.Project;
import org.jboss.forge.project.dependencies.Dependency;
import org.jboss.forge.project.dependencies.DependencyBuilder;
import org.jboss.forge.project.dependencies.ScopeType;
import org.jboss.forge.project.facets.DependencyFacet;
import org.jboss.forge.project.facets.JavaExecutionFacet;
import org.jboss.forge.project.facets.JavaSourceFacet;
import org.jboss.forge.resources.java.JavaResource;
import org.jboss.forge.shell.PromptType;
import org.jboss.forge.shell.Shell;
import org.jboss.forge.shell.events.PickupResource;
import org.jboss.forge.shell.plugins.*;
import org.jboss.forge.shell.util.BeanManagerUtils;
import org.jboss.forge.spec.javaee.CDIFacet;
import org.jboss.seam.forge.arquillian.container.Container;

import javax.enterprise.event.Event;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

@Alias("arquillian")
@RequiresFacet(JavaSourceFacet.class)
@Help("A plugin that helps setting up Arquillian tests")
public class ArquillianPlugin implements Plugin
{
   @Inject
   private Project project;

   @Inject
   BeanManager beanManager;

   @Inject
   private Event<PickupResource> pickup;

   @Inject
   @Current
   private JavaResource resource;

   @Inject
   private Shell shell;

   private String arquillianVersion;

   private DependencyFacet dependencyFacet;

   @Command(value = "setup", help = "Add a container profile to the maven configuration. Multiple containers can exist on a single project.")
   public void setup(@Option(name = "test-framework", defaultValue = "junit", required = false) String testFramework,
                     @Option(name = "container", required = true) ArquillianContainer container,
                     final PipeOut out)
   {

      dependencyFacet = project.getFacet(DependencyFacet.class);

      installArquillianDependency();

      if (testFramework.equals("testng"))
      {
         installTestNgDependencies();
      } else
      {
         installJunitDependencies();
      }

      Container contextualInstance = BeanManagerUtils.getContextualInstance(beanManager, container.getContainer());
      contextualInstance.installDependencies(arquillianVersion);
   }

   private void installArquillianDependency()
   {
      DependencyBuilder arquillianDependency = createArquillianDependency();
      if(dependencyFacet.hasDependency(arquillianDependency)) {
         arquillianVersion = dependencyFacet.getDependency(arquillianDependency).getVersion();
      } else {
         List<Dependency> dependencies = dependencyFacet.resolveAvailableVersions(arquillianDependency);
         Dependency dependency = shell.promptChoiceTyped("Which version of Arquillian do you want to install?", dependencies);
         arquillianVersion = dependency.getVersion();
         dependencyFacet.addDependency(dependency);
      }
   }

   private DependencyBuilder createArquillianDependency()
   {
      return DependencyBuilder.create()
                .setGroupId("org.jboss.arquillian")
                .setArtifactId("arquillian-api")
                .setScopeType(ScopeType.TEST);
   }

   @Command(value = "create-test", help = "Create a new test class with a default @Deployment method")
   public void createTest(
           @Option(name = "class", required = true, type = PromptType.JAVA_CLASS) JavaResource classUnderTest,
           @Option(name = "enableJPA", required = false, flagOnly = true) boolean enableJPA,
           final PipeOut out) throws FileNotFoundException
   {
      JavaSourceFacet java = project.getFacet(JavaSourceFacet.class);

      DependencyFacet dependencyFacet = project.getFacet(DependencyFacet.class);
      boolean junit = dependencyFacet.hasDependency(createJunitDependency());

      JavaSource<?> javaSource = classUnderTest.getJavaSource();

      JavaClass testclass = JavaParser.create(JavaClass.class)
              .setPackage(javaSource.getPackage())
              .setName(javaSource.getName() + "Test")
              .setPublic()
              .addAnnotation("RunWith")
              .setLiteralValue("Arquillian.class")
              .getOrigin();


      if (!junit)
      {
         testclass.setSuperType("Arquillian");
      }

      String testInstanceName = javaSource.getName().toLowerCase();
      testclass.addField()
              .setType(javaSource.getName())
              .setPrivate()
              .setName(testInstanceName)
              .addAnnotation(Inject.class);

      addDeployementMethod(enableJPA, javaSource, testclass);

      addTestMethod(testclass, testInstanceName);

      addImports(dependencyFacet, junit, testclass);

      java.saveTestJavaSource(testclass);

      pickup.fire(new PickupResource(java.getTestJavaResource(testclass)));
   }

   /**
    * This command exports an Archive generated by a @Deployment method to disk. Because the project's classpath is not
    * in the classpath of Forge, the @Deployment method can't be called directly.The plugin works in the following
    * steps: 1 - Generate a new class to the src/test/java folder 2 - Compile the user's classes using mvn test-compile
    * 3 - Run the generated class using mvn exec:java (so that the project's classes are on the classpath) 4 - Delete
    * the generated class
    */
   @Command(value = "export", help = "Export a @Deployment configuration to a zip file on disk.")
   @RequiresResource(JavaResource.class)
   public void exportDeployment(@Option(name = "keepExporter", flagOnly = true) boolean keepExporter, PipeOut out)
   {

      JavaSourceFacet java = project.getFacet(JavaSourceFacet.class);
      try
      {
         JavaResource testJavaResource = java.getTestJavaResource("forge/arquillian/DeploymentExporter.java");
         if (!testJavaResource.exists())
         {
            generateExporterClass(java);
         }

         runExporterClass(out);

         if (!keepExporter)
         {
            testJavaResource.delete();
         }
      } catch (Exception ex)
      {
         throw new RuntimeException("Error while calling generated DeploymentExporter ", ex);
      }
   }

   private void addImports(DependencyFacet dependencyFacet, boolean junit, JavaClass testclass)
   {
      testclass.addImport("javax.enterprise.inject.spi.BeanManager");
      testclass.addImport("javax.inject.Inject");
      testclass.addImport("org.jboss.arquillian.api.Deployment");
      testclass.addImport("org.jboss.arquillian.junit.Arquillian");
      testclass.addImport("org.jboss.shrinkwrap.api.ShrinkWrap");
      testclass.addImport("org.jboss.shrinkwrap.api.ArchivePaths");
      testclass.addImport("org.jboss.shrinkwrap.api.spec.JavaArchive");
      testclass.addImport("org.jboss.shrinkwrap.api.asset.EmptyAsset");

      if (junit)
      {
         testclass.addImport("org.junit.Assert");
         testclass.addImport("org.junit.Test");
         testclass.addImport("org.junit.runner.RunWith");
      } else if (dependencyFacet.hasDependency(createTestNgDependency()))
      {
         testclass.addImport("org.testng.annotations.Test");
      }
   }

   private void addTestMethod(JavaClass testclass, String testInstanceName)
   {
      testclass.addMethod()
              .setName("testIsDeployed")
              .setPublic()
              .setReturnTypeVoid()
              .setBody(createTestMethod(testInstanceName))
              .addAnnotation("Test");
   }

   private void addDeployementMethod(boolean enableJPA, JavaSource<?> javaSource, JavaClass testclass)
   {
      testclass.addMethod()
              .setName("createDeployment")
              .setPublic()
              .setStatic(true)
              .setReturnType("JavaArchive")
              .setBody(createDeploymentFor(javaSource, enableJPA))
              .addAnnotation("Deployment");
   }

   private void runExporterClass(PipeOut out) throws IOException
   {
      JavaExecutionFacet facet = project.getFacet(JavaExecutionFacet.class);
      facet.executeProjectClass("forge.arquillian.DeploymentExporter", resource.getJavaSource().getQualifiedName());
   }

   private void generateExporterClass(JavaSourceFacet java) throws FileNotFoundException
   {
      JavaClass deployementExporterClass = JavaParser.create(JavaClass.class)
              .setPackage("forge.arquillian")
              .setName("DeploymentExporter")
              .setPublic();

      deployementExporterClass.addMethod()
              .setName("main")
              .setPublic()
              .setReturnTypeVoid()
              .setStatic(true)
              .setParameters("String[] args")
              .setBody("try { Class<?> testClass = Class.forName(args[0]);" +
                      "" +
                      "" +
                      "        Method[] methods = testClass.getMethods();" +
                      "        Method deploymentMethod = null;" +
                      "" +
                      "        for (Method method : methods) {" +
                      "            if (method.getAnnotation(Deployment.class) != null) {" +
                      "                deploymentMethod = method;" +
                      "                break;" +
                      "            }" +
                      "        }" +
                      "" +
                      "        Archive<?> archive = (Archive<?>) deploymentMethod.invoke(null);" +
                      "        archive.as(ZipExporter.class).exportTo(new File(archive.getName()), true); } " +
                      "catch(Exception ex) { ex.printStackTrace();} ");

      deployementExporterClass.addImport("org.jboss.arquillian.api.Deployment");
      deployementExporterClass.addImport("org.jboss.shrinkwrap.api.Archive");
      deployementExporterClass.addImport("org.jboss.shrinkwrap.api.exporter.ZipExporter");
      deployementExporterClass.addImport("java.io.File");
      deployementExporterClass.addImport("java.lang.reflect.Method");

      java.saveTestJavaSource(deployementExporterClass);
   }

   private void installJunitDependencies()
   {
      DependencyBuilder junitDependency = createJunitDependency();
      if (!dependencyFacet.hasDependency(junitDependency))
      {
         List<Dependency> dependencies = dependencyFacet.resolveAvailableVersions(junitDependency);
         Dependency dependency = shell.promptChoiceTyped("Which version of JUnit do you want to install?", dependencies);
         dependencyFacet.addDependency(dependency);
      }

      DependencyBuilder junitArquillianDependency = createJunitArquillianDependency();
      if (!dependencyFacet.hasDependency(junitArquillianDependency))
      {
         dependencyFacet.addDependency(junitArquillianDependency);
      }
   }

   private void installTestNgDependencies()
   {
      DependencyBuilder testngDependency = createTestNgDependency();
      if (!dependencyFacet.hasDependency(testngDependency))
      {
         List<Dependency> dependencies = dependencyFacet.resolveAvailableVersions(testngDependency);
         Dependency dependency = shell.promptChoiceTyped("Which version of TestNG do you want to install?", dependencies);
         dependencyFacet.addDependency(dependency);
      }

      DependencyBuilder testNgArquillianDependency = createTestNgArquillianDependency();
      if (!dependencyFacet.hasDependency(testNgArquillianDependency))
      {
         dependencyFacet.addDependency(testNgArquillianDependency);
      }
   }

   private String createTestMethod(String instanceName)
   {
      return "Assert.assertNotNull(" + instanceName + ");";
   }

   private String createDeploymentFor(JavaSource<?> javaSource, boolean enableJPA)
   {
      StringBuilder b = new StringBuilder();
      b.append("return ShrinkWrap.create(JavaArchive.class, \"test.jar\")")
              .append(".addClass(").append(javaSource.getName()).append(".class)");

      if (project.hasFacet(CDIFacet.class))
      {
         b.append(".addAsManifestResource(EmptyAsset.INSTANCE, ArchivePaths.create(\"beans.xml\"))");
      }

      if (enableJPA) // project.hasFacet(PersistenceFacet.class) ?
      {
         b.append(".addAsManifestResource(\"persistence.xml\", ArchivePaths.create(\"persistence.xml\"))");
      }

      b.append(";");
      return b.toString();
   }



   private DependencyBuilder createJunitDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("junit")
              .setArtifactId("junit")
              .setScopeType(ScopeType.TEST);
   }

   private DependencyBuilder createJunitArquillianDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("org.jboss.arquillian")
              .setArtifactId("arquillian-junit")
              .setVersion(arquillianVersion)
              .setScopeType(ScopeType.TEST);
   }

   private DependencyBuilder createTestNgDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("org.testng")
              .setArtifactId("testng")
              .setScopeType(ScopeType.TEST);
   }

   private DependencyBuilder createTestNgArquillianDependency()
   {
      return DependencyBuilder.create()
              .setGroupId("org.jboss.arquillian")
              .setArtifactId("arquillian-testng")
              .setVersion(arquillianVersion)
              .setScopeType(ScopeType.TEST);
   }
}
