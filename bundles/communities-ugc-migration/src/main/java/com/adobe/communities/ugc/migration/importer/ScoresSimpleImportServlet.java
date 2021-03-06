package com.adobe.communities.ugc.migration.importer;

import com.adobe.granite.security.user.UserPropertiesManager;
import com.adobe.granite.security.user.UserPropertiesService;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.apache.felix.scr.annotations.*;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import com.adobe.cq.social.scoring.api.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;


@SuppressWarnings("serial")
@SlingServlet(paths = "/services/social/scores/simple-import",
            methods = "POST",
            metatype = true)
@Properties({
    @Property(name = "service.vendor", value = "UGC Migration Profile Scores Migrate"),
    @Property(name = "service.description", value = "Accepts a json file containing profile scores and applies them to stored profiles")
})
public class ScoresSimpleImportServlet extends SlingAllMethodsServlet {
        
    private static final Logger log = LoggerFactory.getLogger(ScoresSimpleImportServlet.class);
    
    @Reference
    private ResourceResolverFactory resourceResolver;


    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private UserPropertiesService userPropertiesService;

    @Reference
    private ScoringService scoringService;


    /**
    *The post operation accepts a json file, parses it and applies the profile scores to local profiles
    * @param request - the request
    * @param response - the response
    * @throws javax.servlet.ServletException
    * @throws java.io.IOException
    */
    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        ResourceResolver resolver = request.getResourceResolver();
        UGCImportHelper.checkUserPrivileges(resolver, resourceResolver);
        final RequestParameter[] fileRequestParameters = request.getRequestParameters("file");

        String resourcePath = "";
        if(request.getParameter("path") != null && !request.getParameter("path").equals("")){
            resourcePath = request.getParameter("path");
        } else {
            throw new ServletException("No communities-page path entered");
        }

        String scoringRulePath = "";
        if(request.getParameter("scoringRule") != null && !request.getParameter("scoringRule").equals("")){
            scoringRulePath = request.getParameter("scoringRule");
        } else {
            throw new ServletException("No scoring rule path path entered");
        }


        if (fileRequestParameters != null && fileRequestParameters.length > 0 && !fileRequestParameters[0].isFormField()
            && fileRequestParameters[0].getFileName().endsWith(".json")) {
                
            final InputStream inputStream = fileRequestParameters[0].getInputStream();
            final JsonParser jsonParser = new JsonFactory().createParser(inputStream);
            JsonToken token = jsonParser.nextToken();
            
            if (token.equals(JsonToken.START_OBJECT)) {
                try {
                    final UserPropertiesManager userManager = userPropertiesService.createUserPropertiesManager(resolver);
                    readJsonUserScore(jsonParser, resolver, resourcePath, scoringRulePath);
                } catch (RepositoryException e) {
                    throw new ServletException("Unable to communicate with Jcr repository", e);
                }
            } else {
                throw new ServletException("Expected a start object token, got " + token);
            }
        } else {
            log.error("Invalid file");
        }
    }

    private void readJsonUserScore(final JsonParser jsonParser, ResourceResolver resolver, String resourcePath, String scoringRulePath)
            throws ServletException, IOException {

        JsonToken jsonToken = jsonParser.nextToken();

        Resource componentResource = resolver.getResource(resourcePath + "/jcr:content");
        Resource scoreRuleResource = resolver.getResource(scoringRulePath);

        while (!jsonToken.equals(JsonToken.END_OBJECT)) {
            String authId = jsonParser.getCurrentName();
            jsonToken = jsonParser.nextToken();
            Long score = jsonParser.getValueAsLong();

            try {
                scoringService.saveScore(resolver, authId, componentResource, scoreRuleResource, score);
                jsonToken = jsonParser.nextToken();
            } catch (RepositoryException e) {
                throw new ServletException("Unable to communicate with Jcr repository", e);
            }
        }
        log.info("Score Rule Resource:" + scoreRuleResource + " was used to import the scores.");
    }
}