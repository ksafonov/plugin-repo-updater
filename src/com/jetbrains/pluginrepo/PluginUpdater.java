package com.jetbrains.pluginrepo;

import com.sun.org.apache.xpath.internal.XPathAPI;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tools.ant.BuildException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.*;
import java.util.*;

/**
 * User: ksafonov
 */
public class PluginUpdater {
    private static final Log LOG = LogFactory.getLog(UpdatePluginTask.class.getName());

    private static final String REPO_URL = "http://plugins.intellij.net";

    private static class FormParam {
        public final boolean isFile;
        public String value;

        private FormParam(boolean isFile, String value) {
            this.isFile = isFile;
            this.value = value;
        }

        @Override
        public String toString() {
            return "FormParam [" + (isFile ? "file: " : "") + value + "]";
        }
    }

    private static interface MethodFactory {
        HttpMethod createMethod();
    }


    private static List<String> loginAndGetOwnPluginsList(HttpClient httpClient, final String username, final String password) throws IOException {
        HttpMethodWrapper w = new HttpMethodWrapper(httpClient) {
            @Override
            protected HttpMethod createMethod() {
                PostMethod m = new PostMethod(REPO_URL + "/space/");
                Collection<NameValuePair> postParams = new ArrayList<NameValuePair>();
                postParams.add(new NameValuePair("action", "login"));
                postParams.add(new NameValuePair("login", username));
                postParams.add(new NameValuePair("password", password));
                m.setRequestBody(postParams.toArray(new NameValuePair[postParams.size()]));
                return m;
            }
        };

        w.execute();

        int responseCode = w.getStatusCode();

        if (responseCode != HttpStatus.SC_OK) {
            throw new IOException("Request failed with code " + responseCode);
        }
        // TODO check auth cookie?
        String response = w.getResponseBody();
        if (response.contains("Wrong login or password!")) {
            throw new BuildException("Login failed");
        }
        else {
            Document document = parseHtml(w.getResponseBody());
            try {
                NodeList pluginNodes = XPathAPI.selectNodeList(document, "//td[@class=\"name_plugin\"]/a");
                List<String> result = new ArrayList<String>(pluginNodes.getLength());
                for (int i = 0; i < pluginNodes.getLength(); i++) {
                    Node pluginNode = pluginNodes.item(i);
                    Node href = pluginNode.getAttributes().getNamedItem("href");
                    if (href == null) {
                        throw new BuildException("Failed to parse output: " + response);
                    }
                    String url = href.getNodeValue();
                    String marker = "id=";
                    result.add(url.substring(url.indexOf(marker) + marker.length()));
                }
                return result;
            }
            catch (TransformerException e) {
                throw new BuildException("Failed to parse output: " + e.getMessage() + ", response: " + response, e);
            }
        }
    }

    private static boolean checkPluginExistence(HttpClient httpClient, final String pluginId) throws IOException {
        HttpMethodWrapper w = new HttpMethodWrapper(httpClient) {
            @Override
            protected HttpMethod createMethod() {
                return new GetMethod(REPO_URL + "/plugin/?id=" + pluginId);
            }
        };

        w.execute();
        int status = w.getStatusCode();
        if (status == HttpStatus.SC_OK) {
            return true;
        }
        else if (status == HttpStatus.SC_MOVED_TEMPORARILY) {
            return false;
        }
        else {
            throw new IOException("Request failed with code " + status);
        }
    }

    private static LinkedHashMap<String, FormParam> readCurrentValues(HttpClient httpClient, final String pluginId) throws IOException {
        HttpMethodWrapper w = new HttpMethodWrapper(httpClient) {
            @Override
            protected HttpMethod createMethod() {
                return new GetMethod(REPO_URL + "/plugin/edit/?pid=" + pluginId);
            }
        };

        w.execute();

        int status = w.getStatusCode();
        if (status == HttpStatus.SC_OK) {
            return readFormParams(w.getResponseBody());
        }
        else if (status == HttpStatus.SC_MOVED_PERMANENTLY) {
            throw new IOException("Authorization session invalid, please try again");
        }
        else {
            throw new IOException("Request failed with code " + status);
        }
    }

    private static Document parseHtml(String response) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(response.getBytes("UTF-8")));
        }
        catch (ParserConfigurationException e) {
            throw new BuildException("Failed to parse HTML response: " + e.getMessage() + ", response: " + response, e);
        }
        catch (SAXException e) {
            throw new BuildException("Failed to parse HTML response: " + e.getMessage() + ", response: " + response, e);
        }
        catch (IOException e) {
            throw new BuildException("Failed to parse HTML response: " + e.getMessage() + ", response: " + response, e);
        }
    }

    private static LinkedHashMap<String, FormParam> readFormParams(String response) {
        LinkedHashMap<String, FormParam> result = new LinkedHashMap<String, FormParam>();
        Document document = parseHtml(response);
        try {
            Node formNode = XPathAPI.selectSingleNode(document, "//form");
            if (formNode == null) {
                throw new BuildException("Failed to parse HTML response: <form> element not found: " + response);
            }
            NodeList inputNodes = XPathAPI.selectNodeList(formNode, "//input|//textarea|//select");
            for (int i = 0; i < inputNodes.getLength(); i++) {
                Node input = inputNodes.item(i);
                Node nameAttr = input.getAttributes().getNamedItem("name");
                if (nameAttr == null) {
                    LOG.warn("Input with no name");
                    continue;
                }

                Node disabledAttr = input.getAttributes().getNamedItem("disabled");
                if (disabledAttr != null) {
                    continue;
                }

                String name = nameAttr.getNodeValue();

                String inputType;
                if ("input".equals(input.getNodeName())) {
                    Node typeAttr = input.getAttributes().getNamedItem("type");
                    if (typeAttr == null) {
                        LOG.warn("Input '" + input.getNodeName() + "' with no type");
                        continue;
                    }

                    inputType = typeAttr.getNodeValue();
                    if ("radio".equals(inputType)) {
                        // ignore options that are not selected
                        Node checkedAttr = input.getAttributes().getNamedItem("checked");
                        if (checkedAttr == null) {
                            continue;
                        }
                    }
                }
                else if ("select".equals(input.getNodeName())) {
                    inputType = "select";
                }
                else if ("textarea".equals(input.getNodeName())) {
                    inputType = "textarea";
                }
                else {
                    throw new IllegalStateException("Unexpected case: " + input.getNodeName());
                }

                if (result.containsKey(name)) {
                    LOG.warn("Duplicate form data key: " + name);
                    continue;
                }

                if ("file".equals(inputType)) {
                    result.put(name, new FormParam(true, null));
                }
                else {
                    String value;
                    if ("textarea".equals(inputType)) {
                        value = "";
                    }
                    else if ("select".equals(inputType)) {
                        Node selectedOption = XPathAPI.selectSingleNode(input, "option[@selected]");
                        if (selectedOption == null) {
                            LOG.warn(inputType + " '" + name + "' with no selected option");
                            continue;
                        }
                        value = getValue(selectedOption);
                    }
                    else {
                        value = getValue(input);
                    }

                    if (value == null) {
                        LOG.warn(inputType + " '" + name + "' with no value");
                        continue;
                    }
                    result.put(name, new FormParam(false, value));
                }
            }
        }
        catch (TransformerException e) {
            throw new BuildException("Failed to parse HTML response: " + response, e);
        }

        return result;
    }

    private static String getValue(Node input) {
        Node valueAttr = input.getAttributes().getNamedItem("value");
        return valueAttr == null ? null : valueAttr.getNodeValue();
    }

    private static void updatePlugin(HttpClient c, final String pluginId, Map<String, FormParam> values) {
        final ArrayList<Part> parts = new ArrayList<Part>();
        for (Map.Entry<String, FormParam> entry : values.entrySet()) {
            FormParam paramValue = entry.getValue();
            if (paramValue.isFile) {
                PartSource partSource;
                if (paramValue.value != null) {
                    try {
                        partSource = new FilePartSource(new File(paramValue.value));
                    }
                    catch (FileNotFoundException e) {
                        throw new BuildException("File '" + paramValue.value + "' not found for parameter '" + entry.getKey() + "'", e);
                    }
                }
                else {
                    partSource = new ByteArrayPartSource("", new byte[0]);
                }
                FilePart part = new FilePart(entry.getKey(), partSource);
                part.setTransferEncoding(null);
                parts.add(part);
            }
            else {
                StringPart part = new StringPart(entry.getKey(), paramValue.value, "UTF-8");
                part.setTransferEncoding(null);
                part.setContentType(null);
                parts.add(part);
            }
        }

        HttpMethodWrapper w = new HttpMethodWrapper(c) {
            @Override
            protected HttpMethod createMethod() {
                PostMethod m = new PostMethod(REPO_URL + "/plugin/edit/?pid=" + pluginId);
                m.setRequestEntity(new MultipartRequestEntity(parts.toArray(new Part[parts.size()]), m.getParams()));
                return m;
            }
        };

        try {
            w.execute();
        }
        catch (IOException e) {
            throw new BuildException("Failed to execute request: " + e.getMessage(), e);
        }
        int responseCode = w.getStatusCode();
        if (responseCode == HttpStatus.SC_OK) {
            try {
                String response = w.getResponseBody();
                Document document = parseHtml(response);
                Node node = XPathAPI.selectSingleNode(document, "//p[@style=\"color:#ff0000;\"]");
                if (node == null) {
                    throw new BuildException("Request failed, and we failed to find the error message, response: " + response);
                }
                String errorMessage = node.getTextContent();
                if (!errorMessage.isEmpty()) {
                    throw new BuildException("Failed to update plugin: " + errorMessage.trim());
                }
            }
            catch (TransformerException e) {
                throw new BuildException("Request failed, and we failed to parse the response: " + e.getMessage() + ", response: " + w.getResponseBody(), e);
            }
        }
        else if (responseCode != HttpStatus.SC_MOVED_TEMPORARILY) {
            throw new BuildException("Update request failed " + responseCode);
        }
    }

    public static void execute(String pluginId, String username, String password, Map<String, String> newValues) {
        HttpClient c = new HttpClient();
//        c.getHostConfiguration().setProxyHost(new ProxyHost("localhost", 8888));
        try {
            LOG.info("Checking plugin existence...");
            if (!checkPluginExistence(c, pluginId)) {
                throw new BuildException("Plugin with ID " + pluginId + " does not exist");
            }

            LOG.info("Logging in...");
            List<String> ownPlugins = loginAndGetOwnPluginsList(c, username, password);
            if (!ownPlugins.contains(pluginId)) {
                throw new BuildException("Specified user is not a plugin author");
            }

            LOG.info("Loading current properties...");
            LinkedHashMap<String, FormParam> values = readCurrentValues(c, pluginId);
            for (Map.Entry<String, String> e : newValues.entrySet()) {
                FormParam existingParam = values.get(e.getKey());
                if (existingParam == null) {
                    throw new BuildException("Invalid form parameter: " + e.getKey());
                }
                existingParam.value = e.getValue();
            }

            LOG.info("Updating plugin...");
            updatePlugin(c, pluginId, values);
        }
        catch (IOException e) {
            throw new BuildException("Failed to execute request: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        try {
            Map<String, String> newValues = new HashMap<String, String>();
//            newValues.put("notes", "These are some notes");
//            newValues.put("cid", "87");
//            newValues.put("bugtrackerUrl", "http://bugs11");
//            newValues.put("forumUrl", "http://forum11");
//            newValues.put("docUrl", "http://doc11");
//            newValues.put("docText", "use it like that!");
//            newValues.put("source", "http://sources");
//            newValues.put("notes", "These are some notes");
            newValues.put("file", "C:\\Projects\\test-plugin\\test-plugin.jar");
            execute("99999", "UUU", "XXX", newValues);
        }
        catch (BuildException e) {
            e.printStackTrace();
        }
    }
}
