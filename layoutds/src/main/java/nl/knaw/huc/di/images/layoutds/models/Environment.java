package nl.knaw.huc.di.images.layoutds.models;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;
import nl.knaw.huc.di.images.layoutds.models.pim.IPimObject;
import org.hibernate.annotations.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.*;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
// alter table environment alter column prizepapers set not null;
// alter table environment alter column mainTextAsFullPage set not null;
// alter table environment alter column  aboutasfulltext set not null;

@Entity
@XmlRootElement
public class Environment implements IPimObject {
    private static final Logger LOG = LoggerFactory.getLogger(Environment.class);
    public Environment() {
        this.uuid = UUID.randomUUID();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String address;
    private String elasticSearchIndex;
    private String title;
    private String path;
    @Column
    @Type(type = "text")
    private String mainText;
    @Type(type = "text")
    private String about;
    private boolean categories;
    private boolean language;
    private boolean tags;
    private boolean persons;
    private boolean locations;
    private boolean organisations;
    private boolean misc;
    private boolean pro;
    private boolean events;
    private boolean textsize;
    private boolean height;
    private boolean width;
    private boolean ocrConfidence;
    private boolean deskew;
    private boolean columnCount;
    private boolean lineCount;
    private boolean year;
    private boolean month;
    private boolean day;
    private boolean showBadOcr;
    private boolean wordCount;
    private boolean indices;
    private boolean prizePapers;
    private String searchLabel;
    private String logoClass;
    private String topBarClasses;
    private boolean mainTextAsFullPage;
    private boolean aboutAsFullText;
    private boolean publish;
    @Type(type = "text")
    private String instructions;


    @Column(nullable = false, unique = true)
    private UUID uuid;

    @Override
    public UUID getUuid() {
        return uuid;
    }

    @Override
    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAddress() {
        return address;
    }

    public String getDomain() {
        String domain = getAddress();
        if(!Strings.isNullOrEmpty(domain)) {
            URL url;
            try {
                url = new URL(getAddress());
                domain = url.getHost();
            } catch (MalformedURLException e) {
                LOG.error("Unexpected error", e);
            }
        }
        return domain;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getElasticSearchIndex() {
        return elasticSearchIndex;
    }

    public void setElasticSearchIndex(String elasticSearchIndex) {
        this.elasticSearchIndex = elasticSearchIndex;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMainText() {
        return mainText;
    }

    public void setMainText(String mainText) {
        this.mainText = mainText;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getWorkTitle(){
        String worktitle = getTitle().toLowerCase();
        worktitle =
                Normalizer
                        .normalize(worktitle, Normalizer.Form.NFD)
                        .replaceAll("[^\\p{ASCII}]", "");
        worktitle = worktitle.replaceAll("[^A-Za-z0-9]", "_");
        //capitalize
        worktitle = worktitle.substring(0, 1).toUpperCase() + worktitle.substring(1);

        return worktitle;
    }

    public String getOrbTitle() {
        return getPath()+"orb";
    }


    public Boolean isCategories() {
        return categories;
    }

    public void setCategories(Boolean categories) {
        this.categories = categories;
    }

    public Boolean isLanguage() {
        return language;
    }

    public void setLanguage(Boolean language) {
        this.language = language;
    }

    public boolean isTags() {
        return tags;
    }

    public Boolean isPersons() {
        return persons;
    }

    public void setPersons(Boolean persons) {
        this.persons = persons;
    }

    public Boolean isLocations() {
        return locations;
    }

    public void setLocations(Boolean locations) {
        this.locations = locations;
    }

    public Boolean isOrganisations() {
        return organisations;
    }

    public void setOrganisations(Boolean organisations) {
        this.organisations = organisations;
    }

    public Boolean isMisc() {
        return misc;
    }

    public void setMisc(Boolean misc) {
        this.misc = misc;
    }

    public Boolean isPro() {
        return pro;
    }

    public void setPro(Boolean pro) {
        this.pro = pro;
    }

    public Boolean isEvents() {
        return events;
    }

    public void setEvents(Boolean events) {
        this.events = events;
    }

    public Boolean isTextsize() {
        return textsize;
    }

    public void setTextsize(Boolean textsize) {
        this.textsize = textsize;
    }

    public Boolean isHeight() {
        return height;
    }

    public void setHeight(Boolean height) {
        this.height = height;
    }

    public Boolean isWidth() {
        return width;
    }

    public void setWidth(Boolean width) {
        this.width = width;
    }

    public Boolean isOcrConfidence() {
        return ocrConfidence;
    }

    public void setOcrConfidence(Boolean ocrConfidence) {
        this.ocrConfidence = ocrConfidence;
    }

    public Boolean isDeskew() {
        return deskew;
    }

    public void setDeskew(Boolean deskew) {
        this.deskew = deskew;
    }

    public Boolean isColumnCount() {
        return columnCount;
    }

    public void setColumnCount(Boolean columnCount) {
        this.columnCount = columnCount;
    }

    public Boolean isLineCount() {
        return lineCount;
    }

    public void setLineCount(Boolean lineCount) {
        this.lineCount = lineCount;
    }

    public Boolean isYear() {
        return year;
    }

    public void setYear(Boolean year) {
        this.year = year;
    }

    public Boolean isMonth() {
        return month;
    }

    public void setMonth(Boolean month) {
        this.month = month;
    }

    public Boolean isDay() {
        return day;
    }

    public void setDay(Boolean day) {
        this.day = day;
    }

    public Boolean getShowBadOcr() {
        return showBadOcr;
    }

    public void setShowBadOcr(Boolean showBadOcr) {
        this.showBadOcr = showBadOcr;
    }


    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = about;
    }

    public boolean isWordCount() {
        return wordCount;
    }

    public void setWordCount(boolean wordCount) {
        this.wordCount = wordCount;
    }

    public boolean isIndices() {
        return this.indices;
    }

    public void setIndices(Boolean indices) {
        this.indices = indices;
    }

    public Boolean isPrizePapers() {
        return prizePapers;
    }

    public void setPrizePapers(Boolean prizePapers) {
        this.prizePapers = prizePapers;
    }

    public String getSearchLabel() {
        return searchLabel;
    }

    public void setSearchLabel(String searchLabel) {
        this.searchLabel = searchLabel;
    }

    @ElementCollection(targetClass=SearchFacet.class)
    @Column
    @JsonIgnore
    @OneToMany(fetch=FetchType.LAZY, mappedBy="environmentId")
    @OrderBy("facetorder ASC")
    private List<SearchFacet> searchFacets;

    @JsonIgnore
    @XmlTransient
    public List<SearchFacet> getSearchFacets() {
        return searchFacets;
    }


    @ElementCollection(targetClass=SearchOrder.class)
    @Column
    @JsonIgnore
    @OneToMany(fetch=FetchType.LAZY, mappedBy="environmentId")
    @OrderBy("searchorder ASC")
    private List<SearchOrder> searchOrders;

    @JsonIgnore
    @XmlTransient
    public List<SearchOrder> getSearchOrders() {
        return searchOrders;
    }

    @JsonIgnore
    public void setSearchFacets(List<SearchFacet> searchFacets) {
        this.searchFacets= searchFacets;
    }

    public String getLogoClass() {
        return logoClass;
    }

    public void setLogoClass(String logoClass) {
        this.logoClass = logoClass;
    }

    public String getTopBarClasses() {
        return topBarClasses;
    }

    public void setTopBarClasses(String topBarClasses) {
        this.topBarClasses = topBarClasses;
    }

    public Boolean isMainTextAsFullPage() {
        return mainTextAsFullPage;
    }

    public void setMainTextAsFullPage(Boolean mainTextAsFullPage) {
        this.mainTextAsFullPage = mainTextAsFullPage;
    }

    public Boolean isAboutAsFullText() {
        return aboutAsFullText;
    }

    public void setAboutAsFullText(Boolean aboutAsFullText) {
        this.aboutAsFullText = aboutAsFullText;
    }

    @ElementCollection(targetClass = OrderByOption.class)
    @Column
    @JsonIgnore
    @OneToMany(fetch = FetchType.LAZY, mappedBy = "environmentId")
    @OrderBy("orderbyorder ASC")
    private List<OrderByOption> orderByOptions;

    @JsonIgnore
    @XmlTransient
    public List<OrderByOption> getOrderByOptions() {
        return orderByOptions;
    }

    @JsonIgnore
    public void setOrderByOptions(List<OrderByOption> orderByOptions) {
        this.orderByOptions = orderByOptions;
    }

    public Boolean isPublish() {
        return publish;
    }

    public Boolean getPublish() {
        return publish;
    }

    public void setPublish(Boolean publish) {
        this.publish = publish;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getGenerateApacheConfig(){
        String config ="<VirtualHost *:80>\n" +
                "        Servername "+ this.getDomain() +"\n" +
                "        ServerAdmin rutger.van.koert@di.huc.knaw.nl\n" +
                "        DocumentRoot /var/www/html/" + this.getWorkTitle()+
                "\n" +
                "        ErrorLog ${APACHE_LOG_DIR}/"+ this.getWorkTitle()+"-error.log\n" +
                "        CustomLog ${APACHE_LOG_DIR}/"+ this.getWorkTitle()+"-access.log combined\n" +
                "\n" +
                "\n" +
                "        <Directory /var/www/html>\n" +
                "        Options Indexes FollowSymLinks MultiViews\n" +
                "            AllowOverride All\n" +
                "            Require all granted\n" +
                "        </Directory>\n" +
                "\n" +
                "\n" +
                "        ProxyPreserveHost On\n" +
                "        ProxyRequests Off\n" +
                "\n" +
                "        ProxyPass /elastic/" + this.getWorkTitle()+"/ http://localhost:9006/api/pim/elastic/"+ this.getWorkTitle()+"/\n" +
                "        ProxyPassReverse /elastic/" + this.getWorkTitle()+"/ http://localhost:9006/api/pim/elastic/"+ this.getWorkTitle()+ "/\n" +
                "\n" +
                "        ProxyPass /api/ http://analyzerwebservice:9006/api/\n" +
                "        ProxyPassReverse /api/ http://analyzerwebservice:9006/api/\n" +
                "\n" +
                "        ProxyPass /upload/ http://analyzerwebservice:9006/upload/\n" +
                "        ProxyPassReverse /upload/ http://analyzerwebservice:9006/upload/\n" +
                "\n" +
                "        ProxyPass /iiif/ http://loris/iiif/\n" +
                "        ProxyPassReverse /iiif/ http://loris/iiif/\n" +
                "\n" +
                "        Header set Access-Control-Allow-Origin \"*\"\n" +
                "        Header set Access-Control-Allow-Credentials true\n" +
                "        Header set Access-Control-Expose-Headers \"Content-Disposition\"\n" +
                "\n" +
                "</VirtualHost>\n" +
                "" +
                "" +
                "<IfModule mod_ssl.c>\n" +
                "        <VirtualHost _default_:443>\n" +
                "                ServerAdmin rutger.van.koert@di.huc.knaw.nl\n" +
                "                ServerName " + this.getDomain() + "\n" +
                "\n" +
                "                DocumentRoot /var/www/html/" + this.getWorkTitle() + "\n" +
                "\n" +
                "                ErrorLog ${APACHE_LOG_DIR}/error.log\n" +
                "                CustomLog ${APACHE_LOG_DIR}/access.log combined\n" +
                "\n" +
                "                SSLEngine on\n" +
                "\n" +
                "                SSLCertificateFile      /etc/ssl/certs/apache-selfsigned.crt\n" +
                "                SSLCertificateKeyFile /etc/ssl/private/apache-selfsigned.key\n" +
                "\n" +
                "                <FilesMatch \"\\.(cgi|shtml|phtml|php)$\">\n" +
                "                                SSLOptions +StdEnvVars\n" +
                "                </FilesMatch>\n" +
                "                <Directory /usr/lib/cgi-bin>\n" +
                "                                SSLOptions +StdEnvVars\n" +
                "                </Directory>\n" +
                "\n" +
                "        ProxyPreserveHost On\n" +
                "        ProxyRequests Off\n" +
                "\n" +
                "        ProxyPass /api/ http://analyzerwebservice:9006/api/\n" +
                "        ProxyPassReverse /api/ http://analyzerwebservice:9006/api/\n" +
                "\n" +
                "        ProxyPass /upload/ http://analyzerwebservice:9006/upload/\n" +
                "        ProxyPassReverse /upload/ http://analyzerwebservice:9006/upload/\n" +
                "\n" +
                "        ProxyPass /iiif/ http://loris/iiif/\n" +
                "        ProxyPassReverse /iiif/ http://loris/iiif/\n" +
                "\n" +
                "        RewriteEngine On\n" +
                "        RewriteCond %{REQUEST_METHOD} !^(GET|POST|HEAD)\n" +
                "        RewriteRule .* - [R=405,L]\n" +
                "\n" +
                "Header set Access-Control-Allow-Origin \"*\"\n" +
                "Header set Access-Control-Allow-Credentials true\n" +
                "\n" +
                "\n" +
                "                BrowserMatch \"MSIE [2-6]\" \\\n" +
                "                               nokeepalive ssl-unclean-shutdown \\\n" +
                "                               downgrade-1.0 force-response-1.0\n" +
                "\n" +
                "        </VirtualHost>\n" +
                "</IfModule>\n";
        return config;
    }

}
