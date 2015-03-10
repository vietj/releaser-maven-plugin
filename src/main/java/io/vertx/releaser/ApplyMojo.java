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
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

// https://vzurczak.wordpress.com/2014/04/04/no-plugin-found-for-prefix/
// mvn io.vertx:releaser-maven-plugin:sort
@Mojo(name = "apply", aggregator = true)
public class ApplyMojo extends AbstractReleaserMojo {

  @Override
  protected void execute(Map<MavenProject, String> projects) throws MojoExecutionException, MojoFailureException {
    // Now execute the versions set plugin on the projects
    try {
      for (Map.Entry<MavenProject, String> entry : projects.entrySet()) {
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
    for (MavenProject project : projects.keySet()) {
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
