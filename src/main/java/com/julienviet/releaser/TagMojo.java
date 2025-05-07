package com.julienviet.releaser;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;

import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@Mojo(name = "tag", aggregator = true)
public class TagMojo extends AbstractReleaserMojo {

  @Override
  protected void execute(List<MavenProject> projects) throws MojoExecutionException, MojoFailureException {
    for (MavenProject project : projects) {

      try {

        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-scm-plugin");
        plugin.setVersion("1.9.2");
        PluginDescriptor pluginDesc = pluginManager.loadPlugin(plugin, pluginRepos, repoSession);

        // Get version
        String version = project.getArtifact().getVersion();

        // Add modified pom.xml
        MojoDescriptor tagDesc = pluginDesc.getMojo("tag");
        Xpp3Dom tagConfDom = new Xpp3Dom("configuration");
        Xpp3Dom pushChangesDom = new Xpp3Dom("pushChanges");
        pushChangesDom.setValue("false");
        Xpp3Dom basedirDom = new Xpp3Dom("basedir");
        basedirDom.setValue(project.getBasedir().getAbsolutePath());
        Xpp3Dom messageDom = new Xpp3Dom("message");
        messageDom.setValue("Tagging " + version);
        Xpp3Dom tagDom = new Xpp3Dom("tag");
        tagDom.setValue(version);
        tagConfDom.addChild(pushChangesDom);
        tagConfDom.addChild(basedirDom);
        tagConfDom.addChild(messageDom);
        tagConfDom.addChild(tagDom);
        MojoExecution tagExec = new MojoExecution(tagDesc, Xpp3DomUtils.mergeXpp3Dom(tagConfDom, toXpp3Dom(tagDesc.getMojoConfiguration())));

        // Execute mojos
        mavenSession.setCurrentProject(project);
        pluginManager.executeMojo(mavenSession, tagExec);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
