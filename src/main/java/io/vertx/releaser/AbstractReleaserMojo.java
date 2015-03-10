package io.vertx.releaser;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// https://vzurczak.wordpress.com/2014/04/04/no-plugin-found-for-prefix/
// mvn io.vertx:releaser-maven-plugin:sort
public abstract class AbstractReleaserMojo extends AbstractMojo {

  @Parameter(required = true, defaultValue = "${project}", readonly = true)
  protected MavenProject mavenProject;

  @Parameter(required = true, defaultValue = "${session}", readonly = true)
  protected MavenSession mavenSession;

  @Parameter(required = true)
  protected Dependency dependencies;

  @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true )
  protected List<MavenProject> reactorProjects;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  protected RepositorySystemSession repoSession;

  @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
  protected List<RemoteRepository> projectRepos;

  @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true, required = true)
  protected List<RemoteRepository> pluginRepos;

  @Component
  protected RepositorySystem repoSystem;

  @Component()
  protected BuildPluginManager pluginManager;

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

    // Determine the version for each module or fail (maybe there is a better way to link a project and its modules)
    Set<File> modules = new HashSet<File>();
    for (String module : mavenProject.getModules()) {
      File moduleDir = new File(mavenProject.getFile().toURI().resolve(module));
      File modulePom = new File(moduleDir, "pom.xml");
      modules.add(modulePom);
    }

    Map<MavenProject, String> projects = new IdentityHashMap<MavenProject, String>();
    for (MavenProject project : mavenSession.getResult().getTopologicallySortedProjects()) {
      if (modules.contains(project.getFile())) {
        String version = versions.get(project.getGroupId() + ":" + project.getArtifactId());
        if (version != null) {
          System.out.println(project.getGroupId() + ":" + project.getArtifactId() + " -> " + project.getParent());
          projects.put(project, version);
        } else {
          throw new MojoExecutionException("Missing version for project " + project.getGroupId() + ":" + project.getArtifact());
        }
      }
    }

    System.out.println("Determine projects version:");
    for (Map.Entry<MavenProject, String> entry : projects.entrySet()) {
      System.out.println(entry.getKey().getGroupId() + ":" + entry.getKey().getArtifactId() + "-> " + entry.getValue());
    }

    execute(projects);
  }

  protected abstract void execute(Map<MavenProject, String> projects) throws MojoExecutionException, MojoFailureException;

  /**
   * Converts PlexusConfiguration to a Xpp3Dom.
   *
   * @param config the PlexusConfiguration. Must not be {@code null}.
   * @return the Xpp3Dom representation of the PlexusConfiguration
   */
  static Xpp3Dom toXpp3Dom(PlexusConfiguration config) {
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
  static Location[] findRange(XMLStreamReader xmlStream) throws XMLStreamException {
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
