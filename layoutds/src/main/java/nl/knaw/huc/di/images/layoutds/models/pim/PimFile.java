package nl.knaw.huc.di.images.layoutds.models.pim;


import com.fasterxml.jackson.annotation.JsonIgnore;
import nl.knaw.huc.di.images.layoutds.models.pim.IPimObject;
import nl.knaw.huc.di.images.layoutds.models.pim.PimUser;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

@Entity
@XmlRootElement
@Table(indexes = {
        @Index(columnList = "id", name = "pimFile_id_hidx"),
        @Index(columnList = "uri", name = "pimFile_uri_hidx"),
        @Index(columnList = "remoteuri", name = "pimFile_remoteuri_hidx")
})

public class PimFile implements IPimObject {
    @Type(type = "text")
    private String originalFileName;
    private String sha512;

    public PimFile() {
        this.uuid = UUID.randomUUID();
    }

    @Column
    @Type(type = "text")
    private String uri;

    @Type(type = "text")
    private String remoteuri;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long size;

    @Column(nullable = false, unique = true)
    private UUID uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaderId")
    @JsonIgnore
    private PimUser uploader;

    @Override
    public UUID getUuid() {
        return uuid;
    }

    @Override
    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getRemoteuri() {
        return remoteuri;
    }

    public void setRemoteuri(String remoteuri) {
        this.remoteuri = remoteuri;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public PimUser getUploader() {
        return uploader;
    }

    public void setUploader(PimUser uploader) {
        this.uploader = uploader;
    }

    public void setSha512(String sha512) {
        this.sha512 = sha512;
    }

    public String getSha512() {
        return sha512;
    }
}