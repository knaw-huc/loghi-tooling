package nl.knaw.huc.di.images.layoutds.models;

import nl.knaw.huc.di.images.layoutds.models.pim.IPimObject;

import javax.persistence.*;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

@Entity
@XmlRootElement
public class FontDescription implements IPimObject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String uri;

    @Column(nullable = false, unique = true)
    private UUID uuid;

    private String type;
    private String license;
    private Boolean handwritten;
    private Boolean containsLongS;
    private Boolean containsLigatures;
    private Boolean isRomanScript;
    private Boolean isSerif;


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public Boolean getHandwritten() {
        return handwritten;
    }

    public void setHandwritten(Boolean handwritten) {
        this.handwritten = handwritten;
    }

    public Boolean getContainsLongS() {
        return containsLongS;
    }

    public void setContainsLongS(Boolean containsLongS) {
        this.containsLongS = containsLongS;
    }

    public Boolean getContainsLigatures() {
        return containsLigatures;
    }

    public void setContainsLigatures(Boolean containsLigatures) {
        this.containsLigatures = containsLigatures;
    }

    public Boolean getRomanScript() {
        return isRomanScript;
    }

    public void setRomanScript(Boolean romanScript) {
        isRomanScript = romanScript;
    }

    public Boolean getSerif() {
        return isSerif;
    }

    public void setSerif(Boolean serif) {
        isSerif = serif;
    }
}