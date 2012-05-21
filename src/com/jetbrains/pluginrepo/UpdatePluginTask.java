package com.jetbrains.pluginrepo;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import java.util.HashMap;
import java.util.Map;

/**
 * User: ksafonov
 */
@SuppressWarnings("UnusedDeclaration")
public class UpdatePluginTask extends Task {

    public static class Parameter {
        private String name;
        private String value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    private String myUsername;
    private String myPassword;
    private String myPluginId;
    private Map<String, String> myParameters = new HashMap<String, String>();

    public void setUsername(String myUsername) {
        this.myUsername = myUsername;
    }

    public void setPassword(String myPassword) {
        this.myPassword = myPassword;
    }

    public void setPluginId(String myPluginId) {
        this.myPluginId = myPluginId;
    }

    public void addConfiguredParameter(final Parameter parameter) {
        myParameters.put(parameter.getName(), parameter.getValue());
    }

    @Override
    public void execute() throws BuildException {
        if (myPluginId == null) {
            throw new BuildException("No plugin id specified");
        }
        if (myUsername == null) {
            throw new BuildException("No username specified");
        }
        if (myPassword == null) {
            throw new BuildException("No password specified");
        }
        if (myParameters.isEmpty()) {
            throw new BuildException("No parameters specified");
        }
        PluginUpdater.execute(myPluginId, myUsername, myPassword, myParameters);
    }

}
