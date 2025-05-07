package com.julienviet.releaser;

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

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.stream.Collectors;

// https://vzurczak.wordpress.com/2014/04/04/no-plugin-found-for-prefix/
// mvn io.vertx:releaser-maven-plugin:sort
public abstract class AbstractReleaserMojo extends AbstractMojo {

  @Parameter(required = true, defaultValue = "${project}", readonly = true)
  protected MavenProject mavenProject;

  @Parameter(required = true, defaultValue = "${session}", readonly = true)
  protected MavenSession mavenSession;

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
    List<MavenProject> projects = new ArrayList<>(mavenSession.getResult().getTopologicallySortedProjects());

    mavenSession.getProjectMap();

    //
    Set<File> module = mavenSession
      .getCurrentProject()
      .getModules()
      .stream()
      .map(decl -> new File(mavenSession.getCurrentProject().getBasedir(), decl))
      .collect(Collectors.toSet());

    // Filter top level
    Iterator<MavenProject> it = projects.iterator();
    while (it.hasNext()) {
      MavenProject project = it.next();
      if (!module.contains(project.getBasedir())) {
        it.remove();
      } else {
        System.out.println("Adding " + project);
      }
    }

    execute(new ArrayList<>(projects));
  }

  protected abstract void execute(List<MavenProject> projects) throws MojoExecutionException, MojoFailureException;

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
}
