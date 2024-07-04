package de.henrikrueping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectModelResolver;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TODO Klasse kommentieren
 *
 * @author Henrik.Rueping
 * @version $Revision:$<br>
 *     $Date:$<br>
 *     $Author:$
 */
class PomPropertyResolutionTest {
  private static List<RemoteRepository> remoteRepositories;
  private static RepositorySystem repoSystem;
  private static ArtifactResolver artifactResolver;
  //  private static MetadataResolver metadataResolver;
  private static RepositorySystemSession session;
  private static ProjectModelResolver modelResolver;

  @SuppressWarnings("deprecation")
  @BeforeAll
  // in this setup-Method I reconfigure maven so that the local
  static void setup(@TempDir Path tempDir) throws IOException {
    URL url =
        PomPropertyResolutionTest.class
            .getClassLoader()
            .getResource("mockedMavenRepo"); // the repository with the test-Poms

    Path localMavenRepository = tempDir.resolve("maven-repo");
    Files.createDirectories(localMavenRepository);
    String urlString = url.toString();
    remoteRepositories =
        Arrays.asList(
            new RemoteRepository.Builder(
                    urlString.substring(urlString.lastIndexOf('/') + 1), "default", urlString)
                .setPolicy(
                    new RepositoryPolicy(
                        true,
                        RepositoryPolicy.UPDATE_POLICY_NEVER,
                        RepositoryPolicy.CHECKSUM_POLICY_IGNORE))
                .build());
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    // locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
    repoSystem = locator.getService(RepositorySystem.class);
    artifactResolver = locator.getService(ArtifactResolver.class);
    //    metadataResolver = locator.getService(MetadataResolver.class);

    DefaultRepositorySystemSession s = MavenRepositorySystemUtils.newSession()
        // .setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER)
        // .setArtifactDescriptorPolicy(new ErrorToleratingArtifactDescriptorPolicy())
        // .setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true) // damit man an
        // premanaged
        // .setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true)
        // würde auch doppelte anzeigen, bei denen der Konflikt irgendwie aufgelöst
        // wurde.
        // .setSystemProperty("java.version", "11")
        // .setSystemProperty("java.home", System.getProperty("java.home"))
        ;
    // DESWEGEN MUSS JAVA.HOME ab jetzt gesetzt sein.
    s.setLocalRepositoryManager(
        repoSystem.newLocalRepositoryManager(
            s, new LocalRepository(localMavenRepository.toFile(), "default")));
    session = s;
    modelResolver =
        new ProjectModelResolver(
            session,
            new RequestTrace(null),
            repoSystem,
            locator.getService(RemoteRepositoryManager.class),
            remoteRepositories,
            ProjectBuildingRequest.RepositoryMerging.REQUEST_DOMINANT,
            null);
  }

  @SuppressWarnings("deprecation")
  public Model getModel(String artifactName)
      throws ArtifactResolutionException, ModelBuildingException {
    org.eclipse.aether.artifact.Artifact pomArtifact =
        artifactResolver
            .resolveArtifact(
                session,
                new ArtifactRequest(
                    new DefaultArtifact(artifactName),
                    remoteRepositories,
                    "request.getRequestContext()"))
            .getArtifact();
    ModelBuildingRequest modelRequest =
        new DefaultModelBuildingRequest()
            //            .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
            .setProcessPlugins(true)
            .setTwoPhaseBuilding(true) // does not resolve imported boms yet.
            //            .setSystemProperties(
            //                toProperties(session.getUserProperties(),
            // session.getSystemProperties()))
            .setModelResolver(modelResolver)
            .setPomFile(pomArtifact.getFile());

    ModelBuilder modelBuilder =
        MavenRepositorySystemUtils.newServiceLocator().getService(ModelBuilder.class);
    if (modelBuilder == null) {
      modelBuilder = new DefaultModelBuilderFactory().newInstance();
    }
    return modelBuilder.build(modelRequest).getEffectiveModel();
  }

  @Test
  // Obtaining the model now throws a Null-pointer Exception
  void testWithSelfReferencingProperty()
      throws ArtifactResolutionException, ModelBuildingException {
    String artifactName = "groupId:self-referencing-property:pom:1.0.0";
    Model m = getModel(artifactName);
    // this does not meet my expectations. If the property 'prop' refers to itself, a
    // Null-PointerException is thrown.
    Xpp3Dom x =
        (Xpp3Dom) m.getBuild().getPlugins().get(0).getExecutions().get(0).getConfiguration();
    String parsedValue = x.getChild(0).getChild(0).getChild(0).getChild(0).getAttribute("value");
    assertEquals("${prop}", parsedValue);
  }

  @Test
  void testWithoutSelfReferencingProperty()
      throws ArtifactResolutionException, ModelBuildingException {
    String artifactName = "groupId:undefined-property:pom:1.0.0";
    Model m = getModel(artifactName);
    Xpp3Dom x =
        (Xpp3Dom) m.getBuild().getPlugins().get(0).getExecutions().get(0).getConfiguration();
    String parsedValue = x.getChild(0).getChild(0).getChild(0).getChild(0).getAttribute("value");
    // this case meets my expectations. If the property 'prop' cannot be resolved, the string
    // "${prop}" is in the model
    assertEquals("${prop}", parsedValue);
  }

  @Test
  void testWithCorrectProperty() throws ArtifactResolutionException, ModelBuildingException {
    String artifactName = "groupId:defined-property:pom:1.0.0";
    Model m = getModel(artifactName);
    Xpp3Dom x =
        (Xpp3Dom) m.getBuild().getPlugins().get(0).getExecutions().get(0).getConfiguration();
    String parsedValue = x.getChild(0).getChild(0).getChild(0).getChild(0).getAttribute("value");
    // this case meets my expectations. If the property 'prop' cannot be resolved, the string
    // "${prop}" is in the model
    assertEquals("correctValue", parsedValue);
  }
}
