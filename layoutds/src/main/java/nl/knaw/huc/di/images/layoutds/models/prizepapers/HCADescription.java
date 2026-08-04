package nl.knaw.huc.di.images.layoutds.models.prizepapers;


import com.google.common.base.Strings;
import nl.knaw.huc.di.images.layoutds.models.pim.IPimObject;
import org.hibernate.annotations.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.*;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Entity
@XmlRootElement
public class HCADescription implements IPimObject {
    private static final Logger LOG = LoggerFactory.getLogger(HCADescription.class);
    public HCADescription() {
        this.uuid = UUID.randomUUID();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String code;
    @Type(type = "text")
    private String shipName;
    @Type(type = "text")
    private String shipNames;
    @Type(type = "text")
    private String shipMasters;
    @Type(type = "text")
    private String scanNumbers;
    private String yearCapture;
    @Type(type = "text")
    private String remarks;
    private String shipNameTna;
    private String shipMasterOrigin;
    private String infoIntern;
    private String shipOrigin;


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
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getShipName() {
        return shipName;
    }

    public void setShipName(String shipName) {
        this.shipName = shipName;
    }

    public String getShipNames() {
        return shipNames;
    }


    private static HashMap<String, String> getShipOrigins(HCADescription hcaDescription){
        HashMap<String,String> origins = new HashMap<>();
        String[] splitted = hcaDescription.getShipOrigin().split("\n");
        for (String aSplitted : splitted) {
            String key = aSplitted.substring(0, aSplitted.indexOf(" ") + 1).trim();
            String value = aSplitted.substring(aSplitted.indexOf(" ") + 1).trim();

            origins.put(key, value);

        }
        return origins;
    }

    private static HashMap<String, String> getMasterOrigins(HCADescription hcaDescription){
        HashMap<String,String> origins = new HashMap<>();
        String[] splitted = hcaDescription.getShipMasterOrigin().split("\n");
        for (String aSplitted : splitted) {
            String key = aSplitted.substring(0, aSplitted.indexOf(" ") + 1).trim();
            String value = aSplitted.substring(aSplitted.indexOf(" ") + 1).trim();

            origins.put(key, value);

        }
        return origins;
    }


    public List<String> getPrettyShipnames(){
        List<String> shipnames= new ArrayList<>();
        if (getShipName() != null && !getShipName().isEmpty()) {
            String shipname = getShipName();
            if (!Strings.isNullOrEmpty(getShipOrigin())){
                shipname+=" ("+getShipOrigin().trim()+")";
            }
            shipnames.add(shipname);
        }

        if (getShipNames() != null && !getShipNames().isEmpty()) {
            String[] splitted = getShipNames().split("\n");
//            HashMap<String,String> ships = new HashMap<>();
            HashMap<String, String> origins = getShipOrigins(this);

            for (String aSplitted : splitted) {
                String key = aSplitted.substring(0, aSplitted.indexOf(" ") + 1).trim();
                String value = aSplitted.substring(aSplitted.indexOf(" ") + 1).trim();

//                ships.put(key, value);

                String shipname = value;
                if (!Strings.isNullOrEmpty(origins.get(key))) {
                    shipname += " (" + origins.get(key).trim() + ")";
                }
                if (!Strings.isNullOrEmpty(shipname)) {
                    LOG.info("{}", shipname);
                    shipnames.add(shipname);
                }
            }
        }
        return shipnames;
    }


    public void setShipNames(String shipNames) {
        this.shipNames = shipNames;
    }

    public List<String> getPrettyShipMasters() {
        List<String> shipmasters = new ArrayList<>();

        if (getShipMasters() != null && !getShipMasters().isEmpty()) {
            String[] splitted = getShipMasters().split("\n");
            if (splitted.length == 1 && !Strings.isNullOrEmpty(splitted[0])) {
                String shipmaster = splitted[0].trim();
                if (!Strings.isNullOrEmpty(getShipMasterOrigin())) {
                    shipmaster += " (" + getShipMasterOrigin() + ")";
                }
                shipmasters.add(shipmaster);
            }
            if (splitted.length > 1) {
                HashMap<String, String> masterOrigins = getMasterOrigins(this);
                for (String aSplitted : splitted) {
                    String shipmaster = aSplitted.substring(aSplitted.indexOf(" ") + 1).trim();
                    if (!Strings.isNullOrEmpty(shipmaster)) {
                        LOG.warn("{}", shipmaster);
                        shipmasters.add(shipmaster);
                    }
                }
            }
        }
        return shipmasters;
    }

    public String getShipMasters() {
        return shipMasters;
    }

    public void setShipMasters(String shipMasters) {
        this.shipMasters = shipMasters;
    }

    public String getScanNumbers() {
        return scanNumbers;
    }

    public void setScanNumbers(String scanNumbers) {
        this.scanNumbers = scanNumbers;
    }

    public String getYearCapture() {
        return yearCapture;
    }

    public void setYearCapture(String yearCapture) {
        this.yearCapture = yearCapture;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public void setShipNameTna(String shipNameTna) {
        this.shipNameTna = shipNameTna;
    }

    public String getShipNameTna() {
        return shipNameTna;
    }

    public void setShipMasterOrigin(String shipMasterOrigin) {
        this.shipMasterOrigin = shipMasterOrigin;
    }

    public String getShipMasterOrigin() {
        return shipMasterOrigin;
    }

    public void setInfoIntern(String infoIntern) {
        this.infoIntern = infoIntern;
    }

    public String getInfoIntern() {
        return infoIntern;
    }

    public void setShipOrigin(String shipOrigin) {
        this.shipOrigin = shipOrigin;
    }

    public String getShipOrigin() {
        return shipOrigin;
    }
}