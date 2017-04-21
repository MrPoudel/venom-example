/**
 * Created by Ralf D. Mueller and Alexander Heusingfeld
 * https://github.com/rdmueller/asciidoc2confluence
 *
 * this script expects an HTML document created with AsciiDoctor
 * in the following style (default AsciiDoctor output)
 * <div class="sect1">
 *     <h2>Page Title</h2>
 *     <div class="sectionbody">
 *         <div class="sect2">
 *            <h3>Sub-Page Title</h3>
 *         </div>
 *         <div class="sect2">
 *            <h3>Sub-Page Title</h3>
 *         </div>
 *     </div>
 * </div>
 * <div class="sect1">
 *     <h2>Page Title</h2>
 *     ...
 * </div>
 *
 */

// some dependencies
/**
@Grapes(
        [@Grab('org.jsoup:jsoup:1.8.2'),
         @Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.6' ),
         @Grab('org.apache.httpcomponents:httpmime:4.5.1')]
)
**/
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.nodes.Entities.EscapeMode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Document.OutputSettings
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import groovyx.net.http.RESTClient
import groovyx.net.http.HttpResponseException
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.EncoderRegistry
import groovyx.net.http.ContentType
import java.security.MessageDigest
//to upload attachments:
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.entity.mime.content.InputStreamBody
import org.apache.http.entity.mime.HttpMultipartMode
import groovyx.net.http.Method

def baseUrl

// configuration
def config
try {
    println "scriptBasePath: ${scriptBasePath}"
    config = new ConfigSlurper().parse(new File(scriptBasePath, 'ConfluenceConfig.groovy').text)
} catch(groovy.lang.MissingPropertyException e) {
    //no scriptBasePath, works for some szenarios
    config = new ConfigSlurper().parse(new File('scripts/ConfluenceConfig.groovy').text)
}

def confluenceSpaceKey
def confluenceCreateSubpages
def confluencePagePrefix

// helper functions

def MD5(String s) {
    MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
}

// for getting better error message from the REST-API
void trythis (Closure action) {
    try {
        action.run()
    } catch (HttpResponseException error) {
        println "something went wrong - got an http response code "+error.response.status+":"
        println error.response.data
        throw error
    }
}


def parseAdmonitionBlock(block, String type) {
    content = block.select(".content").first()
    titleElement = content.select(".title")
    titleText = ''
    if(titleElement != null) {
        titleText = "<ac:parameter ac:name=\"title\">${titleElement.text()}</ac:parameter>"
        titleElement.remove()
    }
    block.after("<ac:structured-macro ac:name=\"${type}\">${titleText}<ac:rich-text-body>${content}</ac:rich-text-body></ac:structured-macro>")
    block.remove()
}

def uploadAttachment = { def pageId, String url, String fileName, String note ->
    def is
    def localHash
    if (url.startsWith('http')) {
        is = new URL(url).openStream()
        //build a hash of the attachment
        localHash = MD5(new URL(url).openStream().text)
    } else {
        is = new File(url).newDataInputStream()
        //build a hash of the attachment
        localHash = MD5(new File(url).newDataInputStream().text)
    }

    //https://docs.atlassian.com/confluence/REST/latest/
    def api = new RESTClient(config.confluenceAPI)
    //this fixes the encoding
    api.encoderRegistry = new EncoderRegistry( charset: 'utf-8' )

    def headers = [
            'Authorization': 'Basic ' + config.confluenceCredentials,
            'X-Atlassian-Token':'no-check'
    ]
    //check if attachment already exists
    def result = "nothing"
    def attachment = api.get(path: 'content/' + pageId + '/child/attachment',
            query: [
                    'filename': fileName,
            ], headers: headers).data
    def http
    if (attachment.size==1) {
        // attachment exists. need an update?
        def remoteHash = attachment.results[0].extensions.comment.replaceAll("(?sm).*#([^#]+)#.*",'$1')
        if (remoteHash!=localHash) {
            //hash is different -> attachment needs to be updated
            http = new HTTPBuilder(config.confluenceAPI + 'content/' + pageId + '/child/attachment/' + attachment.results[0].id + '/data')
            println "    updated attachment"
        }
    } else {
        http = new HTTPBuilder(config.confluenceAPI + 'content/' + pageId + '/child/attachment')
    }
    if (http) {
        http.request(Method.POST) { req ->
            requestContentType: "multipart/form-data"
            MultipartEntity multiPartContent = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE)
            // Adding Multi-part file parameter "file"
            multiPartContent.addPart("file", new InputStreamBody(is, fileName))
            // Adding another string parameter "comment"
            multiPartContent.addPart("comment", new StringBody(note + "\r\n#" + localHash + "#"))
            req.setEntity(multiPartContent)
            headers.each { key, value ->
                req.addHeader(key, value)
            }
        }
    }
}
//modify local page in order to match the internal confluence storage representation a bit better
//definition lists are not displayed by confluence, so turn them into tables
//body can be of type Element or Elements
def deferredUpload = []
def parseBody =  { body ->
    body.select('div.paragraph').unwrap()
    body.select('div.ulist').unwrap()
    body.select('div.sect3').unwrap()
    [   'note':'info',
        'warning':'warning',
        'important':'warning',
        'caution':'note',
        'tip':'tip'            ].each { adType, cType ->
        body.select('.admonitionblock.'+adType).each { block ->
            parseAdmonitionBlock(block, cType)
        }
    }
    //special for the arc42-template
    body.select('div.arc42help').select('.content')
            .wrap('<ac:structured-macro ac:name="expand"></ac:structured-macro>')
            .wrap('<ac:rich-text-body></ac:rich-text-body>')
            .wrap('<ac:structured-macro ac:name="info"></ac:structured-macro>')
            .before('<ac:parameter ac:name="title">arc42</ac:parameter>')
            .wrap('<ac:rich-text-body><p></p></ac:rich-text-body>')
    body.select('div.arc42help').unwrap()
    body.select('div.title').wrap("<strong></strong>").before("<br />").wrap("<div></div>")
    body.select('div.listingblock').wrap("<p></p>").unwrap()
    // see if we can find referenced images and fetch them
    new File("tmp/images/.").mkdirs()
    // find images, extract their URLs for later uploading (after we know the pageId) and replace them with this macro:
    // <ac:image ac:align="center" ac:width="500">
    // <ri:attachment ri:filename="deployment-context.png"/>
    // </ac:image>
    body.select('img').each { img ->
        img.attributes().each { attribute ->
            //println attribute.dump()
        }
        def src = img.attr('src')
        println "    image: "+src
        def newUrl = baseUrl.toString().replaceAll('\\\\','/').replaceAll('/[^/]*$','/')+src
        def fileName = (src.tokenize('/')[-1])

        trythis {
            deferredUpload <<  [0,newUrl,fileName,"automatically uploaded"]
        }
        img.after("<ac:image ac:align=\"center\" ac:width=\"500\"><ri:attachment ri:filename=\"${fileName}\"/></ac:image>")
        img.remove()
    }
    //change some html elements through simple substitutions
    pageString = body.html().trim()
            .replaceAll("<pre class=\".+\"><code( class=\".+\" data-lang=\".+\")?>", "<ac:structured-macro ac:name=\\\"code\\\"><ac:plain-text-body><![CDATA[")
            .replaceAll("</code></pre>", "]]></ac:plain-text-body></ac:structured-macro>")
            .replaceAll('<dl>','<table><tr>')
            .replaceAll('</dl>','</tr></table>')
            .replaceAll('<dt[^>]*>','<tr><th>')
            .replaceAll('</dt>','</th>')
            .replaceAll('<dd>','<td>')
            .replaceAll('</dd>','</td></tr>')
            .replaceAll('<br>','<br />')
            .replaceAll('</br>','<br />')
            .replaceAll('<a([^>]*)></a>','')
}

// the create-or-update functionality for confluence pages
def pushToConfluence = { pageTitle, pageBody, parentId ->
    def api = new RESTClient(config.confluenceAPI)
    def headers = [
            'Authorization': 'Basic ' + config.confluenceCredentials,
            'Content-Type':'application/json; charset=utf-8'
    ]
    //this fixes the encoding
    api.encoderRegistry = new EncoderRegistry( charset: 'utf-8' )
    //try to get an existing page
    def page
    localPage = parseBody(pageBody)

    def localHash = MD5(localPage)
    localPage  = '<p><ac:structured-macro ac:name="toc"/></p>'+localPage
    localPage += '<p><ac:structured-macro ac:name="children"/></p>'
    localPage += '<p style="display:none">hash: #'+localHash+'#</p>'

    def request = [
            type : 'page',
            title: confluencePagePrefix + pageTitle,
            space: [
                    key: confluenceSpaceKey
            ],
            body : [
                    storage: [
                            value         : localPage,
                            representation: 'storage'
                    ]
            ]
    ]
    if (parentId) {
        request.ancestors = [
                [ type: 'page', id: parentId]
        ]
    }
    trythis {
        page = api.get(path: 'content',
                query: [
                        'spaceKey': confluenceSpaceKey,
                        'title'   : confluencePagePrefix + pageTitle,
                        'expand'  : 'body.storage,version'
                ], headers: headers).data.results[0]
    }
    if (page) {
        //println "found existing page: " + page.id +" version "+page.version.number

        //extract hash from remote page to see if it is different from local one

        def remotePage = page.body.storage.value.toString().trim()

        def remoteHash = remotePage =~ /(?ms)hash: #([^#]+)#/
        remoteHash = remoteHash.size()==0?"":remoteHash[0][1]

        if (remoteHash == localHash) {
            //println "page hasn't changed!"
            deferredUpload.each {
                uploadAttachment(page?.id, it[1], it[2], it[3])
            }
            deferredUpload = []
            return page.id
        } else {
            trythis {
                // update page
                // https://developer.atlassian.com/display/CONFDEV/Confluence+REST+API+Examples#ConfluenceRESTAPIExamples-Updatingapage
                request.id      = page.id
                request.version = [number: (page.version.number as Integer) + 1]
                def res = api.put(contentType: ContentType.JSON,
                        requestContentType : ContentType.JSON,
                        path: 'content/' + page.id, body: request, headers: headers)
            }
            println "> updated page"+page.id
            deferredUpload.each {
                uploadAttachment(page.id, it[1], it[2], it[3])
            }
            deferredUpload = []
            return page.id
        }
    } else {
        //create a page
        trythis {
            page = api.post(contentType: ContentType.JSON,
                    requestContentType: ContentType.JSON,
                    path: 'content', body: request, headers: headers)
        }
        println "> created page "+page?.data?.id
        deferredUpload.each {
            uploadAttachment(page?.data?.id, it[1], it[2], it[3])
        }
        deferredUpload = []
        return page?.data?.id
    }
}

config.input.each { input ->

    println "${input.file}"
    if (input.file ==~ /.*[.](ad|adoc|asciidoc)$/) {
        println "convert ${input.file}"
        "groovy asciidoc2html.groovy ${input.file}".execute()
        input.file = input.file.replaceAll(/[.](ad|adoc|asciidoc)$/,'.html')
        println "to ${input.file}"
    }
    confluenceSpaceKey = input.spaceKey?:config.confluenceSpaceKey
    confluenceCreateSubpages = (input.createSubpages!= null)?input.createSubpages:config.confluenceCreateSubpages
    confluencePagePrefix = input.pagePrefix?:config.confluencePagePrefix

    def html =input.file?new File(input.file).getText('utf-8'):new URL(input.url).getText()
    baseUrl  =input.file?new File(input.file):new URL(input.url)
    Document dom = Jsoup.parse(html, 'utf-8', Parser.xmlParser())
    dom.outputSettings().prettyPrint(false);//makes html() preserve linebreaks and spacing
    dom.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml); //This will ensure xhtml validity regarding entities
    dom.outputSettings().charset("UTF-8"); //does no harm :-)
    def masterid = input.ancestorId

    // if confluenceAncestorId is not set, create a new parent page
    if (!input.ancestorId) {
        input.ancestorId = null
    }

    // let's try to select the "first page" and push it to confluence
    dom.select('div#preamble div.sectionbody').each { pageBody ->
        pageBody.select('div.sect2').unwrap()
        masterid = pushToConfluence "arc42", pageBody, input.ancestorId
    }
    // <div class="sect1"> are the main headings
    // let's extract these and push them to confluence
    dom.select('div.sect1').each { sect1 ->
        def pageTitle = sect1.select('h2').text()
        Elements pageBody = sect1.select('div.sectionbody')
        def subPages = []

        if (confluenceCreateSubpages) {
            pageBody.select('div.sect2').each { sect2 ->
                def title = sect2.select('h3').text()
                sect2.select('h3').remove()
                def body = sect2
                subPages << [
                        title: title,
                        body: body
                ]
                sect2.select('h4').tagName('h1').before('<br />')
                sect2.select('h5').tagName('h2').before('<br />')
                sect2.select('h6').tagName('h3').before('<br />')
                sect2.select('h7').tagName('h4').before('<br />')
            }
            pageBody.select('div.sect2').remove()
        } else {
            pageBody.select('div.sect2').unwrap()
        }
        println pageTitle
        def thisSection = pushToConfluence pageTitle, pageBody, masterid
        subPages.each { subPage ->
            println "   "+subPage.title
            pushToConfluence subPage.title, subPage.body, thisSection
        }
    }
}
""