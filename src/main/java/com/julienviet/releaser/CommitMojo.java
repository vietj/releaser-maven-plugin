package com.julienviet.releaser;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;

import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@Mojo(name = "commit", aggregator = true)
public class CommitMojo extends AbstractReleaserMojo {

  @Parameter(property = "commitMessage")
  private String commitMessage = "";

  @Override
  protected void execute(List<MavenProject> projects) throws MojoExecutionException, MojoFailureException {
    for (MavenProject project : projects) {

      try {

        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-scm-plugin");
        plugin.setVersion("1.9.2");
        PluginDescriptor pluginDesc = pluginManager.loadPlugin(plugin, pluginRepos, repoSession);

        // Various conf
        Xpp3Dom pushChangesDom = new Xpp3Dom("pushChanges");
        pushChangesDom.setValue("false");
        Xpp3Dom basedirDom = new Xpp3Dom("basedir");
        basedirDom.setValue(project.getBasedir().getAbsolutePath());

        // Add modified pom.xml
        MojoDescriptor addDesc = pluginDesc.getMojo("add");
        Xpp3Dom includesDom = new Xpp3Dom("includes");
        includesDom.setValue("pom.xml");
        Xpp3Dom addConfDom = new Xpp3Dom("configuration");
        addConfDom.addChild(pushChangesDom);
        addConfDom.addChild(basedirDom);
        addConfDom.addChild(includesDom);
        MojoExecution addExec = new MojoExecution(addDesc, Xpp3DomUtils.mergeXpp3Dom(addConfDom, toXpp3Dom(addDesc.getMojoConfiguration())));

        // Commit
        MojoDescriptor checkinDesc = pluginDesc.getMojo("checkin");
        Xpp3Dom messageDom = new Xpp3Dom("message");
        String msg;
        if (commitMessage == null || commitMessage.isEmpty()) {
          String version = project.getArtifact().getVersion();
          msg = "Releasing " + version;
        } else {
          msg = commitMessage;
        }
        messageDom.setValue(msg);
        Xpp3Dom checkinConfDom = new Xpp3Dom("configuration");
        checkinConfDom.addChild(pushChangesDom);
        checkinConfDom.addChild(basedirDom);
        checkinConfDom.addChild(messageDom);
        MojoExecution checkinExec = new MojoExecution(checkinDesc, Xpp3DomUtils.mergeXpp3Dom(checkinConfDom, toXpp3Dom(checkinDesc.getMojoConfiguration())));

        // Execute mojos
        mavenSession.setCurrentProject(project);
        pluginManager.executeMojo(mavenSession, addExec);
        pluginManager.executeMojo(mavenSession, checkinExec);

      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
