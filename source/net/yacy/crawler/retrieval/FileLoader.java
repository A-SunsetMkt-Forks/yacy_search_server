/**
 *  FileLoader
 *  Copyright 2010 by Michael Peter Christen
 *  First released 25.5.2010 at http://yacy.net
 *
 * $LastChangedDate$
 * $LastChangedRevision$
 * $LastChangedBy$
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.crawler.retrieval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.yacy.cora.document.ASCII;
import net.yacy.cora.document.Classification;
import net.yacy.cora.document.UTF8;
import net.yacy.cora.protocol.HeaderFramework;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.cora.protocol.ftp.FTPClient;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.document.TextParser;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.util.FileUtils;
import net.yacy.search.Switchboard;

public class FileLoader {

    private final Switchboard sb;
    private final Log log;
    private final int maxFileSize;

    public FileLoader(final Switchboard sb, final Log log) {
        this.sb = sb;
        this.log = log;
        this.maxFileSize = (int) sb.getConfigLong("crawler.file.maxFileSize", -1l);
    }

    public Response load(final Request request, boolean acceptOnlyParseable) throws IOException {
        DigestURI url = request.url();
        if (!url.getProtocol().equals("file")) throw new IOException("wrong loader for FileLoader: " + url.getProtocol());

        RequestHeader requestHeader = new RequestHeader();
        if (request.referrerhash() != null) {
            DigestURI ur = this.sb.getURL(request.referrerhash());
            if (ur != null) requestHeader.put(RequestHeader.REFERER, ur.toNormalform(true, false));
        }

        // process directories: transform them to html with meta robots=noindex (using the ftpc lib)
        String[] l = null;
        try {l = url.list();} catch (IOException e) {}
        if (l != null) {
            String u = url.toNormalform(true, true);
            List<String> list = new ArrayList<String>();
            for (String s: l) {
                list.add(u + ((u.endsWith("/") || u.endsWith("\\")) ? "" : "/") + s);
            }

            StringBuilder content = FTPClient.dirhtml(u, null, null, null, list, true);

            ResponseHeader responseHeader = new ResponseHeader(200);
            responseHeader.put(HeaderFramework.LAST_MODIFIED, HeaderFramework.formatRFC1123(new Date()));
            responseHeader.put(HeaderFramework.CONTENT_TYPE, "text/html");
            final CrawlProfile profile = this.sb.crawler.getActive(ASCII.getBytes(request.profileHandle()));
            Response response = new Response(
                    request,
                    requestHeader,
                    responseHeader,
                    profile,
                    false,
                    UTF8.getBytes(content.toString()));

            return response;
        }

        // create response header
        String mime = Classification.ext2mime(url.getFileExtension());
        ResponseHeader responseHeader = new ResponseHeader(200);
        responseHeader.put(HeaderFramework.LAST_MODIFIED, HeaderFramework.formatRFC1123(new Date(url.lastModified())));
        responseHeader.put(HeaderFramework.CONTENT_TYPE, mime);

        // check mime type and availability of parsers
        // and also check resource size and limitation of the size
        long size;
        try {
            size = url.length();
        } catch (Exception e) {
            size = -1;
        }
        String parserError = null;
        if ((acceptOnlyParseable && (parserError = TextParser.supports(url, mime)) != null) ||
            (size > this.maxFileSize && this.maxFileSize >= 0)) {
            // we know that we cannot process that file before loading
            // only the metadata is returned

            if (parserError != null) {
                this.log.logInfo("No parser available in File crawler: '" + parserError + "' for URL " + request.url().toString() + ": parsing only metadata");
            } else {
                this.log.logInfo("Too big file in File crawler with size = " + size + " Bytes for URL " + request.url().toString() + ": parsing only metadata");
            }

            // create response with metadata only
            responseHeader.put(HeaderFramework.CONTENT_TYPE, "text/plain");
            final CrawlProfile profile = this.sb.crawler.getActive(ASCII.getBytes(request.profileHandle()));
            Response response = new Response(
                    request,
                    requestHeader,
                    responseHeader,
                    profile,
                    false,
                    UTF8.getBytes(url.toTokens()));
            return response;
        }

        // load the resource
        InputStream is = url.getInputStream(null, -1);
        byte[] b = FileUtils.read(is);
        is.close();

        // create response with loaded content
        final CrawlProfile profile = this.sb.crawler.getActive(ASCII.getBytes(request.profileHandle()));
        Response response = new Response(
                request,
                requestHeader,
                responseHeader,
                profile,
                false,
                b);
        return response;
    }
}