package com.solanolabs.jenkinsplugins.solanocijenkins;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;

import com.sun.syndication.io.FeedException;
import org.codehaus.jettison.json.JSONException;

/**
 * Run a build step on Solano CI {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link SolanoBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked. 
 *
 * @author Isaac Chapman
 */
public class SolanoBuilder extends Builder {

    private static final Integer SL_WEBHOOK_POST_WAIT = 1;//5000; // How many microseconds after sending the webhook should we wait to check the guid?
    private static final Integer SL_API_POLL_WAIT = 15000; // How many microseconds between pooling Solano's API?
    private static final Integer buildTimeout = 1000 * 60 * 15; // How many microseconds until build step times out. TODO: change to GUI setting or read from config file?
    private static final String TDDIUM_CLIENT_VERSION = "tddium-client_0.4.4"; // See http://solano-api-docs.nfshost.com/preliminaries/
    private final String webhookurl;
    private final String apikey;
    private final String apihost;
    private final String branch;
    private final String profile;
    private final boolean force;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public SolanoBuilder(String webhookurl, String branch, String apikey, String apihost, String profile, boolean force) {
        this.webhookurl = webhookurl;
        this.apikey = apikey;
        this.apihost = apihost;
        this.branch = branch;
        this.profile = profile;
        this.force = force;
    }

    public String getWebhookurl() {
        return webhookurl;
    }
    public String getApikey() {
        return apikey;
    }
    public String getApihost() {
        return apihost;
    }
    public String getBranch() {
        return branch;
    }
    public String getProfile() {
        return profile;
    }
    public boolean getForce() {
        return force;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link SolanoBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'apikey'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckApikey(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set an api key");
            if (value.length() != 40)
                return FormValidation.warning("The api key is the wrong length");
            return FormValidation.ok();
        }

        public FormValidation doCheckWebhookurl(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set the web hook url");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Solano CI build step";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            save();
            return super.configure(req,formData);
        }

    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

        String guid;
        String statusUrl;
        String ciEventsURL;
        String sessionId = null;
        Client client;
        WebResource webResource;
        ClientResponse response;
        JSONObject jsonResponse;
        long buildStart;
        boolean buildComplete = false;

        // Build JSON webhook: http://docs.solanolabs.com/Setup/webhooks/#build-trigger-webhooks-incoming
        JSONObject jsonRequest = new JSONObject();
        jsonRequest.put("source", "solano");
        jsonRequest.put("op", "push");
        if (force) {
            jsonRequest.put("force", true);
        } else {
            jsonRequest.put("force", false);
        }
        if (branch != null && !branch.isEmpty()) {
            jsonRequest.put("branch", branch);
        }
        if (profile != null && !profile.isEmpty()) {
            jsonRequest.put("profile_name", profile);
        }

        listener.getLogger().println("Web hook URL: " + webhookurl);
        listener.getLogger().println("Solano API Key: " + apikey);
        listener.getLogger().println("Webhook JSON:\n" + jsonRequest.toString());

        // Send webhook
        try {
            client = Client.create();
            webResource = client.resource(webhookurl);
            response = webResource.type("application/json").post(ClientResponse.class, jsonRequest.toString());
            listener.getLogger().println("Webhook response code: " + response.getStatus());
            jsonResponse = (JSONObject) JSONSerializer.toJSON(response.getEntity(String.class));
            listener.getLogger().println("Webhook response: " + jsonResponse.toString());
            guid = jsonResponse.getString("guid");
            // Save guid? (in case of Jenkins restart?)
            if (apihost != null && !apihost.isEmpty()) {
                ciEventsURL = apihost + "1/ci_events/" + guid;
            } else {
                ciEventsURL = "https://ci.solanolabs.com:443/1/ci_events/" + guid;
            }
            listener.getLogger().println("Solano CI Events endpoint: " + ciEventsURL);
            statusUrl = jsonResponse.getString("status_uri");
            listener.getLogger().println("Status URL: " + statusUrl);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        buildStart = System.currentTimeMillis();

        // Wait a bit for build to be queued
        try {
            Integer napTime = Math.round(SL_WEBHOOK_POST_WAIT/1000);
            listener.getLogger().println("Taking a " + napTime.toString() + " second nap");
            Thread.sleep(SL_WEBHOOK_POST_WAIT);
        } catch (InterruptedException e) {
            // http://www.ibm.com/developerworks/java/library/j-jtp05236/index.html?ca=drs-
            // says I should be doing something here
            listener.getLogger().println("Nap was interupted!?!");
            e.printStackTrace();
            return false;
        }

        // Poll guid: http://solano-api-docs.nfshost.com/reference/ci_events/
        while (!buildComplete && System.currentTimeMillis() < buildStart + buildTimeout) {
            // poll guid
            listener.getLogger().println("Polling Solano CI Events API endpoint");
            listener.getLogger().println(ciEventsURL);
            try {
                client = Client.create();
                webResource = client.resource(ciEventsURL);
                response = webResource.accept("application/json")
                    .header("X-Tddium-Client-Version", TDDIUM_CLIENT_VERSION)
                    .header("X-Tddium-Api-Key", apikey)
                    .get(ClientResponse.class);
                if (response.getStatus() != 200) {
                    listener.getLogger().println("ERROR:");
                    listener.getLogger().println("Solano CI Events URL response code: " + response.getStatus());
                    listener.getLogger().println("Message: " + response.getEntity(String.class));
                    return false;
                }
                jsonResponse = (JSONObject) JSONSerializer.toJSON(response.getEntity(String.class));
                listener.getLogger().println("jsonResponse: " + jsonResponse.toString());
                if (sessionId == null && jsonResponse.getJSONObject("event").has("session_id") && !jsonResponse.getJSONObject("event").getString("session_id").equals("null")) {
                    sessionId = jsonResponse.getJSONObject("event").getString("session_id");
                    listener.getLogger().println("Solano CI Session ID: " + sessionId);
                }
                // Check on status
                String buildStatus = jsonResponse.getJSONObject("event").getString("state");
                if (buildStatus.equals("received") || buildStatus.equals("queued") || buildStatus.equals("building")) {
                    listener.getLogger().println("Status: " + buildStatus);
                } else if (buildStatus.equals("stopped") || buildStatus.equals("dropped")) {
                    listener.getLogger().println("ERROR Status: " + buildStatus);
                    listener.getLogger().println(statusUrl);
                    return false;
                } else if (buildStatus.equals("completed")) {
                    // Check if there is an error message
                    if (jsonResponse.getJSONObject("event").has("error") && !jsonResponse.getJSONObject("event").getString("error").equals("null")) {
                        listener.getLogger().println("ERROR: " + jsonResponse.getJSONObject("event").getString("error"));
                        listener.getLogger().println(statusUrl);
                        return false;
                    }
                    listener.getLogger().println("Status: " + buildStatus);
                    buildComplete = true;
                    break;
                } else {
                    listener.getLogger().println("ERROR: Unhandled CI event state: '" + buildStatus + "'");
                    listener.getLogger().println(statusUrl);
                    return false;
                }
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            if (!buildComplete) {
                try {
                    Integer napTime = Math.round(SL_API_POLL_WAIT/1000);
                    listener.getLogger().println("Taking a " + napTime.toString() + " second nap");
                    Thread.sleep(SL_API_POLL_WAIT);
                } catch (InterruptedException e) {
                    // http://www.ibm.com/developerworks/java/library/j-jtp05236/index.html?ca=drs-
                    // says I should be doing something here
                    listener.getLogger().println("Nap was interupted!?!");
                    e.printStackTrace();
                    return false;
                }
            }
            
        }

        // Its possible the build is not complete but the Jenkins job timed out...
        if (!buildComplete) {
            listener.getLogger().println("ERROR: This Jenkins job has timed out before Solano CI completed.\n" + "Please see: " + statusUrl);
            return false;
        }

        // Grab session data
        try {
            String sessionURL;
            if (apihost != null && !apihost.isEmpty()) {
                sessionURL = apihost + "1/sessions/" + sessionId;
            } else {
                sessionURL = "https://ci.solanolabs.com:443/1/sessions/" + sessionId;
            }
            client = Client.create();
            webResource = client.resource(sessionURL);
            response = webResource.accept("application/json")
                .header("X-Tddium-Client-Version", TDDIUM_CLIENT_VERSION)
                .header("X-Tddium-Api-Key", apikey)
                .get(ClientResponse.class);
            if (response.getStatus() != 200) {
                listener.getLogger().println("ERROR:");
                listener.getLogger().println("Solano Sessions URL response code: " + response.getStatus());
                listener.getLogger().println("Message: " + response.getEntity(String.class));
                return false;
            }
            jsonResponse = (JSONObject) JSONSerializer.toJSON(response.getEntity(String.class));
            listener.getLogger().println("Session JSON:\n" + jsonResponse.toString(2));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        // Transform into Jenkins compatible 

        return true;
    }
}

