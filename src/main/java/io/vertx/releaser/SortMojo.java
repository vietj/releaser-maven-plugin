package io.vertx.releaser;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.release.util.ReleaseUtil;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.PrefixedPropertiesValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// https://vzurczak.wordpress.com/2014/04/04/no-plugin-found-for-prefix/
// mvn io.vertx:releaser-maven-plugin:sort
@Mojo(name = "sort", aggregator = true)
public class SortMojo extends AbstractMojo {

  @Parameter(required = true, defaultValue = "${project}", readonly = true)
  private MavenProject mavenProject;

  @Parameter(required = true, defaultValue = "${session}", readonly = true)
  private MavenSession mavenSession;

  @Parameter(required = true)
  private Dependency dependencies;

  @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true )
  private List<MavenProject> reactorProjects;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  private RepositorySystemSession repoSession;

  @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
  private List<RemoteRepository> projectRepos;

  @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true, required = true)
  private List<RemoteRepository> pluginRepos;

  @Component
  private RepositorySystem repoSystem;

  @Component()
  private BuildPluginManager pluginManager;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    Map<String, String> versions = new HashMap<String, String>();
    try {
      Artifact artifact = new DefaultArtifact(dependencies.getGroupId(), dependencies.getArtifactId(), "pom", dependencies.getVersion());
      ArtifactRequest request = new ArtifactRequest();
      request.setArtifact(artifact);
      request.setRepositories(Collections.<RemoteRepository>emptyList());
      ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);

      File pomFile = result.getArtifact().getFile();

      FileReader fileReader = new FileReader(pomFile);
      MavenXpp3Reader pomReader = new MavenXpp3Reader();
      Model model = pomReader.read(fileReader);
      model.setPomFile(pomFile);
      MavenProject project = new MavenProject(model);

      Interpolator interpolator = new StringSearchInterpolator();
      interpolator.addValueSource(new PropertiesBasedValueSource(project.getProperties()));

      for (Dependency dm : project.getDependencyManagement().getDependencies()) {
        String groupId = dm.getGroupId();
        String artifactId = dm.getArtifactId();
        String version = interpolator.interpolate(dm.getVersion());
        versions.put(groupId + ":" + artifactId, version);
      }

    } catch (Exception e) {
      MojoExecutionException ex = new MojoExecutionException("Cannot resolve dependencies");
      ex.initCause(e);
      throw ex;
    }

    // Determine the version for each module or fail
    Map<MavenProject, String> projectVersions = new IdentityHashMap<MavenProject, String>();
    for (MavenProject project : mavenSession.getResult().getTopologicallySortedProjects()) {
      if (project != mavenProject) {
        String version = versions.get(project.getGroupId() + ":" + project.getArtifactId());
        if (version != null) {
          projectVersions.put(project, version);
        } else {
          throw new MojoExecutionException("Missing version for project " + project.getGroupId() + ":" + project.getArtifact());
        }
      }
    }

    //


    // Now execute the versions set plugin on the projects
    try {
      for (Map.Entry<MavenProject, String> entry : projectVersions.entrySet()) {
        MavenProject project = entry.getKey();
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.codehaus.mojo");
        plugin.setArtifactId("versions-maven-plugin");
        plugin.setVersion("2.1");
        PluginDescriptor pluginDesc = pluginManager.loadPlugin(plugin, pluginRepos, repoSession);
        MojoDescriptor mojoDesc = pluginDesc.getMojo("set");
        Xpp3Dom defaultConfDom = toXpp3Dom(mojoDesc.getMojoConfiguration());
        Xpp3Dom newVersionDom = new Xpp3Dom("newVersion");
        newVersionDom.setValue(entry.getValue());
        Xpp3Dom confDom = new Xpp3Dom("configuration");
        confDom.addChild(newVersionDom);
        confDom = Xpp3DomUtils.mergeXpp3Dom(confDom, defaultConfDom);
        MojoExecution mojoExec = new MojoExecution(mojoDesc, confDom);
        mavenSession.setCurrentProject(project);
        pluginManager.executeMojo(mavenSession, mojoExec);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    mavenSession.setCurrentProject(mavenProject);

    // Rewrite each pom to have the new versions vertx dependencies set
    for (MavenProject project : projectVersions.keySet()) {
      System.out.println("Rewriting " + project.getGroupId() + ":" + project.getArtifactId());
      File pom = ReleaseUtil.getStandardPom(project);
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try {
        Files.copy(pom.toPath(), buffer);
        XMLStreamReader xmlStream = XMLInputFactory.newInstance().createXMLStreamReader(new ByteArrayInputStream(buffer.toByteArray()));
        Location[] range = findRange(xmlStream);
        if (range != null) {
          String encoding = xmlStream.getEncoding();
          String xml = buffer.toString(encoding);
          xml = xml.substring(0, range[0].getCharacterOffset()) + dependencies.getVersion() + xml.substring(range[1].getCharacterOffset() - 2);
          Files.copy(new ByteArrayInputStream(xml.getBytes(encoding)), pom.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Converts PlexusConfiguration to a Xpp3Dom.
   *
   * @param config the PlexusConfiguration. Must not be {@code null}.
   * @return the Xpp3Dom representation of the PlexusConfiguration
   */
  public static Xpp3Dom toXpp3Dom(PlexusConfiguration config) {
    Xpp3Dom result = new Xpp3Dom(config.getName());
    result.setValue(config.getValue(null));
    for (String name : config.getAttributeNames()) {
      result.setAttribute(name, config.getAttribute(name));
    }
    for (PlexusConfiguration child : config.getChildren()) {
      result.addChild(toXpp3Dom(child));
    }
    return result;
  }

  /**
   * @return the range of the 'stack.version' property in the pom.xml file or null when not found
   */
  private static Location[] findRange(XMLStreamReader xmlStream) throws XMLStreamException {
    int status = 0;
    Location from = null, to = null, location = null;
    while (xmlStream.hasNext()) {
      if (xmlStream.isStartElement()) {
        String element = xmlStream.getLocalName();
        if (status == 0 && element.equals("project")) {
          status = 1;
        } else if (status == 1 && element.equals("properties")) {
          status = 2;
        } else if (status == 2 && element.equals("stack.version")) {
          status = 3;
          from = xmlStream.getLocation();
        }

      } else if (xmlStream.isEndElement()) {
        String element = xmlStream.getLocalName();
        if (status == 3 && element.equals("stack.version")) {
          to = location;
          break;
        }
      }
      location = xmlStream.getLocation();
      xmlStream.next();
    }
    return from != null && to != null ? new Location[]{from, to} : null;
  }
}
