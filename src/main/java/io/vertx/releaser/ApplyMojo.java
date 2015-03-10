package io.vertx.releaser;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.release.util.ReleaseUtil;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

// https://vzurczak.wordpress.com/2014/04/04/no-plugin-found-for-prefix/
// mvn io.vertx:releaser-maven-plugin:sort
@Mojo(name = "apply", aggregator = true)
public class ApplyMojo extends AbstractReleaserMojo {

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
}
