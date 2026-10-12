package com.example;

import com.example.model.Equipement;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationService;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.*;

public class RelationsTest {

    // UNE seule usine pour tous les tests (coûteuse à créer) -> recommandation du correcteur
    private static EntityManagerFactory emf;

    // Un EntityManager neuf par test (léger, non thread-safe)
    private EntityManager em;

    private static final LocalDateTime LUNDI_9H = LocalDateTime.of(2026, 10, 19, 9, 0);
    private static final LocalDateTime LUNDI_11H = LocalDateTime.of(2026, 10, 19, 11, 0);
    private static final LocalDateTime MARDI_14H = LocalDateTime.of(2026, 10, 20, 14, 0);
    private static final LocalDateTime MARDI_16H = LocalDateTime.of(2026, 10, 20, 16, 0);

    @BeforeClass
    public static void creerUsine() {
        emf = Persistence.createEntityManagerFactory("gestion-reservations-pu");
    }

    @AfterClass
    public static void fermerUsine() {
        if (emf != null) emf.close();
    }

    @Before
    public void setUp() {
        em = emf.createEntityManager();
    }

    @After
    public void tearDown() {
        // La base est partagée entre les tests : on la vide pour que chaque test parte de zéro
        if (em.getTransaction().isActive()) em.getTransaction().rollback();
        em.getTransaction().begin();
        em.createNativeQuery("DELETE FROM salle_equipement").executeUpdate();
        em.createNativeQuery("DELETE FROM Reservation").executeUpdate();
        em.createNativeQuery("DELETE FROM Utilisateur").executeUpdate();
        em.createNativeQuery("DELETE FROM Salle").executeUpdate();
        em.createNativeQuery("DELETE FROM Equipement").executeUpdate();
        em.getTransaction().commit();
        em.close();
    }

    // ---------- Jeu de données commun ----------

    /** Alice, 2 réservations dans la Salle A, qui contient Projecteur + Tableau blanc. */
    private Utilisateur creerJeuDeDonnees() {
        em.getTransaction().begin();

        Salle salle = new Salle("Salle A", 20);
        salle.addEquipement(new Equipement("Projecteur"));
        salle.addEquipement(new Equipement("Tableau blanc"));

        Utilisateur alice = new Utilisateur("Alice Dupont", "alice@example.com");

        Reservation r1 = new Reservation(LUNDI_9H, LUNDI_11H);
        r1.setSalle(salle);
        alice.addReservation(r1);

        Reservation r2 = new Reservation(MARDI_14H, MARDI_16H);
        r2.setSalle(salle);
        alice.addReservation(r2);

        em.persist(salle);  // + équipements (cascade PERSIST)
        em.persist(alice);  // + réservations (cascade ALL)

        em.getTransaction().commit();
        em.clear();         // on vide le cache : les lectures suivantes iront vraiment en base
        return alice;
    }

    private long compter(String entite) {
        return em.createQuery("SELECT COUNT(e) FROM " + entite + " e", Long.class).getSingleResult();
    }

    // ---------- Cascades et orphelins ----------

    @Test
    public void persistUtilisateur_enregistreAussiSesReservations() {
        Utilisateur alice = creerJeuDeDonnees();

        Utilisateur relu = em.find(Utilisateur.class, alice.getId());
        assertNotNull("L'id doit avoir été généré par la base", relu.getId());
        assertEquals(2, relu.getReservations().size());
        assertEquals(2, compter("Reservation"));
    }

    @Test
    public void manyToMany_salleEtEquipementsNavigablesDansLesDeuxSens() {
        creerJeuDeDonnees();

        Salle salle = em.createQuery("SELECT s FROM Salle s WHERE s.nom = 'Salle A'", Salle.class)
                .getSingleResult();
        assertEquals(2, salle.getEquipements().size());

        Equipement projecteur = em.createQuery(
                        "SELECT e FROM Equipement e WHERE e.nom = 'Projecteur'", Equipement.class)
                .getSingleResult();
        assertEquals(1, projecteur.getSalles().size());
        assertEquals("Salle A", projecteur.getSalles().iterator().next().getNom());
    }

    @Test
    public void orphanRemoval_retirerUneReservationLaSupprimeDeLaBase() {
        Utilisateur alice = creerJeuDeDonnees();

        em.getTransaction().begin();
        Utilisateur relu = em.find(Utilisateur.class, alice.getId());
        Reservation r = relu.getReservations().iterator().next();
        relu.getReservations().remove(r);   // aucun em.remove()
        em.getTransaction().commit();

        assertEquals(1, compter("Reservation"));
    }

    @Test
    public void supprimerUtilisateur_supprimeSesReservationsMaisPasLaSalleNiLesEquipements() {
        Utilisateur alice = creerJeuDeDonnees();

        em.getTransaction().begin();
        em.remove(em.find(Utilisateur.class, alice.getId()));
        em.getTransaction().commit();

        assertEquals(0, compter("Utilisateur"));
        assertEquals(0, compter("Reservation"));
        assertEquals(1, compter("Salle"));
        assertEquals(2, compter("Equipement"));
    }

    // ---------- Scénario d'erreur : rollback ----------

    @Test
    public void emailEnDouble_leveUneExceptionEtRollback() {
        em.getTransaction().begin();
        em.persist(new Utilisateur("Alice", "doublon@example.com"));
        em.getTransaction().commit();

        boolean exceptionLevee = false;
        try {
            em.getTransaction().begin();
            em.persist(new Utilisateur("Bob", "doublon@example.com")); // unique = true violé
            em.getTransaction().commit();
        } catch (PersistenceException e) {
            exceptionLevee = true;
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
        }
        assertTrue("Une exception doit être levée pour un email en double", exceptionLevee);

        // Après une erreur, l'EntityManager n'est plus fiable : on en ouvre un nouveau pour vérifier
        em.close();
        em = emf.createEntityManager();
        assertEquals("Seul le premier utilisateur doit être en base", 1, compter("Utilisateur"));
    }

    // ---------- Requêtes de recherche (ReservationService) ----------

    @Test
    public void reservationsDeUtilisateur_renvoieSesReservationsTrieesParDate() {
        creerJeuDeDonnees();
        ReservationService service = new ReservationService(em);

        List<Reservation> resultat = service.reservationsDeUtilisateur("alice@example.com");

        assertEquals(2, resultat.size());
        assertEquals(LUNDI_9H, resultat.get(0).getDateDebut());
        assertTrue(service.reservationsDeUtilisateur("inconnu@example.com").isEmpty());
    }

    @Test
    public void sallesAvecEquipement_trouveLaSalleQuiALEquipement() {
        creerJeuDeDonnees();
        ReservationService service = new ReservationService(em);

        List<Salle> salles = service.sallesAvecEquipement("Projecteur");

        assertEquals(1, salles.size());
        assertEquals("Salle A", salles.get(0).getNom());
        assertTrue(service.sallesAvecEquipement("Micro").isEmpty());
    }

    @Test
    public void estDisponible_detecteLesChevauchements() {
        creerJeuDeDonnees();
        ReservationService service = new ReservationService(em);
        Salle salle = em.createQuery("SELECT s FROM Salle s WHERE s.nom = 'Salle A'", Salle.class)
                .getSingleResult();

        // Lundi 10h-12h chevauche la réservation 9h-11h
        assertFalse(service.estDisponible(salle, LUNDI_9H.plusHours(1), LUNDI_11H.plusHours(1)));
        // Lundi 11h-12h commence pile quand l'autre finit : pas de chevauchement
        assertTrue(service.estDisponible(salle, LUNDI_11H, LUNDI_11H.plusHours(1)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void estDisponible_refuseUneDateDeFinAvantLaDateDeDebut() {
        Salle salle = new Salle("Salle B", 10);
        new ReservationService(em).estDisponible(salle, LUNDI_11H, LUNDI_9H);
    }
}