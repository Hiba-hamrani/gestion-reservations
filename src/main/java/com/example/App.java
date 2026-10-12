
package com.example;

import com.example.model.*;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.time.LocalDateTime;

public class App {

    public static void main(String[] args) {
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("gestion-reservations-pu");
        try {
            Long userId = etape1Creation(emf);
            etape2SuppressionOrpheline(emf, userId);
            etape3SuppressionUtilisateur(emf, userId);
        } finally {
            emf.close();
        }
    }

    // ÉTAPE 1 : création des entités et persistance en cascade
    private static Long etape1Creation(EntityManagerFactory emf) {
        System.out.println("\n===== ÉTAPE 1 : création =====");
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();

            Utilisateur user = new Utilisateur("Alice Dupont", "alice@example.com");

            Salle salle = new Salle("Salle A", 20);
            Equipement projecteur = new Equipement("Projecteur");
            Equipement tableau = new Equipement("Tableau blanc");
            salle.addEquipement(projecteur);
            salle.addEquipement(tableau);

            Reservation r1 = new Reservation(
                    LocalDateTime.of(2026, 10, 20, 9, 0), LocalDateTime.of(2026, 10, 20, 11, 0));
            r1.setSalle(salle);
            user.addReservation(r1);

            Reservation r2 = new Reservation(
                    LocalDateTime.of(2026, 10, 21, 14, 0), LocalDateTime.of(2026, 10, 21, 16, 0));
            r2.setSalle(salle);
            user.addReservation(r2);

            // La salle n'est pas "possédée" par la réservation : on l'enregistre elle-même.
            // Ses équipements suivent grâce à cascade PERSIST sur Salle.equipements.
            em.persist(salle);

            // L'utilisateur entraîne ses 2 réservations grâce à cascade ALL.
            em.persist(user);

            em.getTransaction().commit();

            System.out.println("Créé : " + user);
            System.out.println("Créée : " + salle + " avec " + salle.getEquipements().size() + " équipements");
            System.out.println("Réservations : " + user.getReservations().size());
            return user.getId();
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    // ÉTAPE 2 : retirer une réservation de la collection -> supprimée grâce à orphanRemoval
    private static void etape2SuppressionOrpheline(EntityManagerFactory emf, Long userId) {
        System.out.println("\n===== ÉTAPE 2 : suppression d'une réservation (orphanRemoval) =====");
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();

            Utilisateur user = em.find(Utilisateur.class, userId);
            Reservation res = user.getReservations().iterator().next();
            user.getReservations().remove(res); // aucun em.remove() !

            em.getTransaction().commit();

            System.out.println("Réservations restantes en base : " + compter(em, "Reservation"));
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    // ÉTAPE 3 : supprimer l'utilisateur -> ses réservations partent avec (cascade REMOVE)
    private static void etape3SuppressionUtilisateur(EntityManagerFactory emf, Long userId) {
        System.out.println("\n===== ÉTAPE 3 : suppression de l'utilisateur (cascade REMOVE) =====");
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();

            Utilisateur user = em.find(Utilisateur.class, userId);
            em.remove(user);

            em.getTransaction().commit();

            System.out.println("Utilisateurs en base  : " + compter(em, "Utilisateur"));
            System.out.println("Réservations en base  : " + compter(em, "Reservation"));
            System.out.println("Salles en base        : " + compter(em, "Salle") + " (non supprimée)");
            System.out.println("Équipements en base   : " + compter(em, "Equipement") + " (non supprimés)");
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    private static long compter(EntityManager em, String entite) {
        return em.createQuery("SELECT COUNT(e) FROM " + entite + " e", Long.class).getSingleResult();
    }
}