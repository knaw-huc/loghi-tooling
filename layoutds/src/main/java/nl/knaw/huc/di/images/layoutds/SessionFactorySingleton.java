package nl.knaw.huc.di.images.layoutds;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

public class SessionFactorySingleton {
    private static volatile SessionFactory sessionFactory;

    public static SessionFactory getSessionFactory() {
        if (sessionFactory == null){
            synchronized (SessionFactorySingleton.class) {
                if (sessionFactory == null) {
                    Configuration configuration = new Configuration();
                    configuration.configure();
                    sessionFactory = configuration.buildSessionFactory();
                }
            }
        }
        return sessionFactory;
    }

    public static void closeSessionFactory() {
        if (sessionFactory!=null){
            sessionFactory.close();
            sessionFactory = null;
        }
    }

}
