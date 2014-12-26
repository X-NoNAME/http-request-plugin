package jenkins.plugins.http_request_params;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.VariableResolver;
import jenkins.plugins.http_request_params.auth.Authenticator;
import jenkins.plugins.http_request_params.auth.BasicDigestAuthentication;
import jenkins.plugins.http_request_params.auth.FormAuthentication;
import jenkins.plugins.http_request_params.util.HttpClientUtil;
import jenkins.plugins.http_request_params.util.NameValuePair;
import jenkins.plugins.http_request_params.util.RequestAction;
import net.sf.json.JSONObject;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.SystemDefaultHttpClient;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Janario Oliveira
 */
public class HttpRequest extends Builder {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRequest.class);
    private final String url;
    private final String parameters;
    private final HttpMode httpMode;
    private final String authentication;
    private final Boolean returnCodeBuildRelevant;
    private final Boolean logResponseBody;

    @DataBoundConstructor
    public HttpRequest(String url, String httpMode,String parameters, String authentication, String returnCodeBuildRelevant, String logResponseBody)
            throws URISyntaxException {
        this.url = url;
        this.parameters = parameters;
        this.httpMode = Util.fixEmpty(httpMode) == null ? null : HttpMode.valueOf(httpMode);
        this.authentication = Util.fixEmpty(authentication);
        if (returnCodeBuildRelevant != null && returnCodeBuildRelevant.trim().length() > 0) {
            this.returnCodeBuildRelevant = Boolean.parseBoolean(returnCodeBuildRelevant);
        } else {
            this.returnCodeBuildRelevant = null;
        }
	
        if (logResponseBody != null && logResponseBody.trim().length() > 0) {
            this.logResponseBody = Boolean.parseBoolean(logResponseBody);
        } else {
            this.logResponseBody = null;
        }
	
    }

    public Boolean getLogResponseBody() {
        return logResponseBody;
    }

    public String getUrl() {
        return url;
    }

    public String getParameters() {
        return parameters;
    }
        
    public HttpMode getHttpMode() {
        return httpMode;
    }

    public String getAuthentication() {
        return authentication;
    }

    public Boolean getReturnCodeBuildRelevant() {
        return returnCodeBuildRelevant;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        final PrintStream logger = listener.getLogger();

        final HttpMode mode = httpMode != null ? httpMode : getDescriptor().getDefaultHttpMode();
        logger.println("HttpMode: " + mode);

        final SystemDefaultHttpClient httpclient = new SystemDefaultHttpClient();

        logger.println("Parameters: ");
        final EnvVars envVars = build.getEnvironment(listener);
        final List<NameValuePair> params = createParameters(build, logger, envVars);
        String evaluatedUrl = evaluate(url, build.getBuildVariableResolver(), envVars);
        logger.println(String.format("URL: %s", evaluatedUrl));
        final RequestAction requestAction = new RequestAction(
                new URL(evaluatedUrl),
                mode,
                params);
        final HttpClientUtil clientUtil = new HttpClientUtil();
        final HttpRequestBase method = clientUtil.createRequestBase(requestAction);

        if (authentication != null) {
            final Authenticator auth = getDescriptor().getAuthentication(authentication);
            if (auth == null) {
                throw new IllegalStateException("Authentication " + authentication + " doesn't exists anymore");
            }

            logger.println("Using authentication: " + auth.getKeyName());
            auth.authenticate(httpclient, method, logger);
        }
	
        boolean tmpLogResponseBody = logResponseBody != null
        ? logResponseBody : getDescriptor().isDefaultLogResponseBody();	

        final HttpResponse execute = clientUtil.execute(httpclient, method, logger, tmpLogResponseBody);

        // use global configuration as default if it is unset for this job
        boolean returnCodeRelevant = returnCodeBuildRelevant != null
                ? returnCodeBuildRelevant : getDescriptor().isDefaultReturnCodeBuildRelevant();
        
        LOGGER.debug("---> config local: {}", returnCodeBuildRelevant);
        LOGGER.debug("---> global: {}", getDescriptor().isDefaultReturnCodeBuildRelevant());
        LOGGER.debug("---> returnCodeRelevant: {}", returnCodeRelevant);

        if (returnCodeRelevant) {
            // return false if status from 400(client error) to 599(server error)
            return !(execute.getStatusLine().getStatusCode() >= 400 && execute.getStatusLine().getStatusCode() <= 599);
        } else {
            // ignore status code from HTTP response
            logger.println("Ignoring return code as " + (returnCodeBuildRelevant != null ? "Local" : "Global") + " configuration");
            return true;
        }
    }

    private List<NameValuePair> createParameters(
            AbstractBuild<?, ?> build, PrintStream logger,
            EnvVars envVars) {
        final VariableResolver<String> vars = build.getBuildVariableResolver();

        Map<String, String> map = getMapFrompParams();
        
        if(map.isEmpty()){
            map.putAll(build.getBuildVariables());
        }
        
        List<NameValuePair> l = new ArrayList<NameValuePair>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String value = evaluate(entry.getValue(), vars, envVars);
            logger.println("  " + entry.getKey() + " = " + value);

            l.add(new NameValuePair(entry.getKey(), value));
        }

        return l;
    }

    private String evaluate(String value, VariableResolver<String> vars, Map<String, String> env) {
        return Util.replaceMacro(Util.replaceMacro(value, vars), env);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private Map<String, String> getMapFrompParams() {
        Map<String, String> retMap = new HashMap<String, String>();
        if(parameters==null || parameters.trim().isEmpty() || !parameters.contains("=")) return null;
        
        String[] rows = parameters.split("\n");
        for(String row:rows){
            if(row.trim().isEmpty() || !row.trim().contains("=")) continue;
            
            String[] pairs = row.trim().split(";");
            for(String pair:pairs){
                String[] keyValue=pair.trim().split("=",2);
                if(keyValue.length==0) continue;
                
                else if (keyValue.length==1){
                    retMap.put(keyValue[0].trim(), "NULL");
                }else {
                    retMap.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        
        return retMap;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        private HttpMode defaultHttpMode = HttpMode.POST;
        private List<BasicDigestAuthentication> basicDigestAuthentications = new ArrayList<BasicDigestAuthentication>();
        private List<FormAuthentication> formAuthentications = new ArrayList<FormAuthentication>();
        private boolean defaultReturnCodeBuildRelevant = true;
	private boolean defaultLogResponseBody = true;

        public DescriptorImpl() {
            load();
        }

	public boolean isDefaultLogResponseBody() {
		return defaultLogResponseBody;
	}

	public void setDefaultLogResponseBody(boolean defaultLogResponseBody) {
		this.defaultLogResponseBody = defaultLogResponseBody;
	}
	
        public HttpMode getDefaultHttpMode() {
            return defaultHttpMode;
        }

        public void setDefaultHttpMode(HttpMode defaultHttpMode) {
            this.defaultHttpMode = defaultHttpMode;
        }

        public List<BasicDigestAuthentication> getBasicDigestAuthentications() {
            return basicDigestAuthentications;
        }

        public void setBasicDigestAuthentications(
                List<BasicDigestAuthentication> basicDigestAuthentications) {
            this.basicDigestAuthentications = basicDigestAuthentications;
        }

        public List<FormAuthentication> getFormAuthentications() {
            return formAuthentications;
        }

        public void setFormAuthentications(
                List<FormAuthentication> formAuthentications) {
            this.formAuthentications = formAuthentications;
        }

        public List<Authenticator> getAuthentications() {
            List<Authenticator> list = new ArrayList<Authenticator>();
            list.addAll(basicDigestAuthentications);
            list.addAll(formAuthentications);
            return list;
        }

        public Authenticator getAuthentication(String keyName) {
            for (Authenticator authenticator : getAuthentications()) {
                if (authenticator.getKeyName().equals(keyName)) {
                    return authenticator;
                }
            }
            return null;
        }

        public boolean isDefaultReturnCodeBuildRelevant() {
            return defaultReturnCodeBuildRelevant;
        }

        public void setDefaultReturnCodeBuildRelevant(boolean defaultReturnCodeBuildRelevant) {
            this.defaultReturnCodeBuildRelevant = defaultReturnCodeBuildRelevant;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "HTTP Request with params";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws
                FormException {
            req.bindJSON(this, formData);
            save();
            return true;
        }

        public ListBoxModel doFillDefaultHttpModeItems() {
            return HttpMode.getFillItems();
        }

        public ListBoxModel doFillHttpModeItems() {
            ListBoxModel items = HttpMode.getFillItems();
            items.add(0, new ListBoxModel.Option("Default", ""));

            return items;
        }

        public ListBoxModel doFillAuthenticationItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("");
            for (BasicDigestAuthentication basicDigestAuthentication : basicDigestAuthentications) {
                items.add(basicDigestAuthentication.getKeyName());
            }
            for (FormAuthentication formAuthentication : formAuthentications) {
                items.add(formAuthentication.getKeyName());
            }

            return items;
        }

        public FormValidation doCheckUrl(@QueryParameter String value)
                throws IOException, ServletException {
            return FormValidation.ok();
            // return HttpRequestValidation.checkUrl(value);
        }

        public FormValidation doValidateKeyName(@QueryParameter String value) {
            List<Authenticator> list = getAuthentications();

            int count = 0;
            for (Authenticator basicAuthentication : list) {
                if (basicAuthentication.getKeyName().equals(value)) {
                    count++;
                }
            }

            if (count > 1) {
                return FormValidation.error("The Key Name must be unique");
            }

            return FormValidation.validateRequired(value);
        }
        
        public ListBoxModel doFillReturnCodeBuildRelevantItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Default", "");
            items.add("Yes", "true");
            items.add("No", "false");
            return items;
        }
	
        public ListBoxModel doFillLogResponseBodyItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Default", "");
            items.add("Yes", "true");
            items.add("No", "false");
            return items;
        }
	
    }
}