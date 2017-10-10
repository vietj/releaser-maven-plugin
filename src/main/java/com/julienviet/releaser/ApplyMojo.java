package com.julienviet.releaser;

import org.apache.maven.model.Plugin;
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
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// https://vzurczak.wordpress.com/2014/04/04/no-plugin-found-for-prefix/
// mvn io.vertx:releaser-maven-plugin:sort
@Mojo(name = "apply", aggregator = true)
public class ApplyMojo extends AbstractReleaserMojo {

  private static final Pattern DECL_PATTERN = Pattern.compile("\\<\\?xml(.+?)\\?\\>");

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
      try {
        String content = rewriteStackVersionProperty(pom, dependencies.getVersion());
        Files.copy(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), pom.toPath(), StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  static String rewriteStackVersionProperty(File pom, String version) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    Files.copy(pom.toPath(), buffer);
    String xml = buffer.toString();
    Matcher matcher = DECL_PATTERN.matcher(xml);
    String decl = "";
    if (matcher.find()) {
      int pos = matcher.end();
      while (pos < xml.length()) {
        if (xml.charAt(pos) == '<') {
          decl = xml.substring(0, pos);
          break;
        } else {
          pos++;
        }
      }
    }
    DocumentBuilderFactory documentBuilderfactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = documentBuilderfactory.newDocumentBuilder();
    Document doc = builder.parse(new ByteArrayInputStream(buffer.toByteArray()));
    XPath xpath = XPathFactory.newInstance().newXPath();
    XPathExpression comp = xpath.compile("//project/properties/stack.version/text()");
    NodeList nodes = (NodeList) comp.evaluate(doc, XPathConstants.NODESET);
    for (int i = 0;i < nodes.getLength();i++) {
      Node node = nodes.item(i);
      node.setNodeValue(version);
    }
    doc.setXmlStandalone(true);
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
    DOMSource source = new DOMSource(doc);
    StringWriter sw = new StringWriter();
    StreamResult result = new StreamResult(sw);
    transformer.transform(source, result);
    return decl + sw;
  }
}
