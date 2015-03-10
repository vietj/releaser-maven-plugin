package io.vertx.releaser;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.List;

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
