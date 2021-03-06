package com.adobe.communities.ugc.migration.importer;

import com.day.text.Text;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.UUID;

public class ImageUploadUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ImageUploadUtil.class);

    private static String IMAGE_ELEMENT = "<img";
    private static String SRC = "src";
    private static String SRC_ELEMENT = SRC + "=";

    private static String UGC_ROOT_DIR = "/content/usergenerated";
    private static String TEMP_ROOT_DIR = "tmp";
    private static String SOCIAL_DIR = "social";
    private static String IMAGES_DIR = "images";
    private static String TEMP_DIR = UGC_ROOT_DIR + "/" + TEMP_ROOT_DIR + "/" + SOCIAL_DIR + "/" + IMAGES_DIR;

    /**
     * Parses the input string for any embedded images (<img>). If any embedded images are found,
     * they are imported into AEM and the original image source will be updated to the new path.
     * @param input The input String containing embedded images
     * @return A modified version of the input string containing references to AEM images.
     */
    public static String importImage(final ResourceResolver resourceResolver, final String input, final String include) {
        String modified = input;

        if (StringUtils.containsIgnoreCase(modified, IMAGE_ELEMENT)) {
            int startPos = 0;
            int index = -1;

            while ((index = StringUtils.indexOfIgnoreCase(modified, IMAGE_ELEMENT, startPos)) >= 0) {
                startPos = index + 1;
                int imageElementEnd = StringUtils.indexOf(modified, ">", index);
                int indexSrc = StringUtils.indexOfIgnoreCase(modified, SRC_ELEMENT, index);
                int imgStartPos = StringUtils.indexOf(modified, "\"", indexSrc);
                if (imgStartPos > 0 && imgStartPos< imageElementEnd) {
                    int imgEndPos = StringUtils.indexOf(modified, "\"", imgStartPos + 1);
                    if (imgEndPos > 0) {
                        String imgUrl = StringUtils.substring(modified, imgStartPos + 1, imgEndPos);
                        imgUrl = Text.unescape(imgUrl);
                        String importedImgUrl = null;

                        // Use filter if specified
                        if (!StringUtils.isEmpty(include) && imgUrl.contains(include)) {
                            importedImgUrl = importImageToJcr(resourceResolver, imgUrl);
                        }

                        // No filter, just import image
                        if (StringUtils.isEmpty(include)) {
                            importedImgUrl = importImageToJcr(resourceResolver, imgUrl);
                        }

                        if (importedImgUrl != null) {
                            modified = replace(modified, importedImgUrl, imgStartPos, imgEndPos - imgStartPos);
                        }
                    }
                }
            }
        }

        return modified;
    }

    private static String importImageToJcr(final ResourceResolver resourceResolver, final String url) {
        if (url == null || StringUtils.isEmpty(url) || url.startsWith(TEMP_DIR)) {
            return null;
        }

        // Don't import images from file system
        if (url.startsWith("file://")) {
            return null;
        }

        String jcrPath = null;
        HttpClient client = new DefaultHttpClient();
        HttpGet get = new HttpGet(url.replaceAll(" ", "%20"));
        HttpResponse response = null;

        // Download image
        try {
            response = client.execute(get);
        } catch (Exception e) {
            LOG.error("Error while downloading image: " + url, e);
        }

        // If the response was successful, proceed with moving image into JCR
        if (response != null && (response.getStatusLine().getStatusCode() >= 200 && response.getStatusLine().getStatusCode() <= 299)) {
            byte[] content = null;
            Node parentNode = null;
            try {
                parentNode = getUgcImagesNode(resourceResolver);
            } catch (RepositoryException e) {
                LOG.error("Error getting UGC images node.", e);
            }

            try {
                content = EntityUtils.toByteArray(response.getEntity());
            } catch (IOException e) {
                LOG.error("Error while reading HTTP response into byte array.", e);
            }

            if (parentNode != null && content != null) {
                String mimeType = response.getEntity().getContentType().toString();
                String extension = getFileExtension(mimeType);
                String fileName = UUID.randomUUID().toString() + extension;
                InputStream is = new ByteArrayInputStream(content);
                Session session = resourceResolver.adaptTo(Session.class);

                try {
                    ValueFactory valueFactory = session.getValueFactory();
                    Binary contentValue = valueFactory.createBinary(is);
                    Node fileNode = parentNode.addNode(fileName, "nt:file");
                    fileNode.addMixin("mix:referenceable");
                    Node resNode = fileNode.addNode("jcr:content", "nt:resource");
                    resNode.setProperty("jcr:mimeType", response.getEntity().getContentType().toString());
                    resNode.setProperty("jcr:data", contentValue);
                    Calendar lastModified = Calendar.getInstance();
                    lastModified.setTimeInMillis(lastModified.getTimeInMillis());
                    resNode.setProperty("jcr:lastModified", lastModified);

                    session.save();
                    jcrPath = fileNode.getPath();
                } catch (RepositoryException e) {
                    LOG.error("Error moving image into JCR.", e);
                }
            }
        }

        return jcrPath;
    }

    private static String getFileExtension(String mimeType) {
        String extension = "";

        if (StringUtils.containsIgnoreCase(mimeType, "gif")) {
            extension = ".gif";
        } else if (StringUtils.containsIgnoreCase(mimeType, "jpg")) {
            extension = ".jpeg";
        } else if (StringUtils.containsIgnoreCase(mimeType, "jpg")) {
            extension = ".jpg";
        } else if (StringUtils.containsIgnoreCase(mimeType, "png")) {
            extension = ".png";
        } else if (StringUtils.containsIgnoreCase(mimeType, "svg+xml")) {
            extension = ".svg";
        }

        return extension;
    }

    private static String replace(String text, String replacement, int startIndex, int length) {
        int replLength = replacement.length();
        int increase = replacement.length() - length;
        increase = increase < 0 ? 0 : increase;
        increase *= 16;
        StringBuilder buf = new StringBuilder(text.length() + increase);
        buf = buf.append(text, 0, startIndex + 1).append(replacement).append(text.substring(startIndex + length));

        return buf.toString();
    }

    private static Node getUgcImagesNode(ResourceResolver resolver) throws RepositoryException {
        Node ugcNode = resolver.getResource(UGC_ROOT_DIR).adaptTo(Node.class);
        Node ret = null;
        if (ugcNode != null) {
            Node tmpNode = JcrUtils.getOrAddFolder(ugcNode, TEMP_ROOT_DIR);
            Node socialNode = JcrUtils.getOrAddFolder(tmpNode, SOCIAL_DIR);
            Node imageNode = JcrUtils.getOrAddFolder(socialNode, IMAGES_DIR);
            ret = imageNode;
        }

        return ret;
    }
}
