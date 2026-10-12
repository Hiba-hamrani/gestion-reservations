package com.example.service;

import com.example.model.Reservation;
import com.example.model.Salle;

import javax.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Requêtes de recherche (exercices supplémentaires du TP).
 * Le service reçoit l'EntityManager : c'est l'appelant qui l'ouvre et le ferme.
 */
public class ReservationService {

    private final EntityManager em;

    public ReservationService(EntityManager em) {
        this.em = em;
    }

    // Les réservations d'un utilisateur, triées par date (jointure implicite r.utilisateur.email)
    public List<Reservation> reservationsDeUtilisateur(String email) {
        return em.createQuery(
                        "SELECT r FROM Reservation r WHERE r.utilisateur.email = :email ORDER BY r.dateDebut",
                        Reservation.class)
                .setParameter("email", email)
                .getResultList();
    }

    // Les salles qui possèdent un équipement donné (JOIN sur la collection ManyToMany)
    public List<Salle> sallesAvecEquipement(String nomEquipement) {
        return em.createQuery(
                        "SELECT s FROM Salle s JOIN s.equipements e WHERE e.nom = :nom",
                        Salle.class)
                .setParameter("nom", nomEquipement)
                .getResultList();
    }

    // Une salle est disponible si aucune réservation ne chevauche la période [debut, fin[
    public boolean estDisponible(Salle salle, LocalDateTime debut, LocalDateTime fin) {
        if (!debut.isBefore(fin)) {
            throw new IllegalArgumentException("La date de début doit être avant la date de fin");
        }
        Long chevauchements = em.createQuery(
                        "SELECT COUNT(r) FROM Reservation r " +
                                "WHERE r.salle = :salle AND r.dateDebut < :fin AND r.dateFin > :debut",
                        Long.class)
                .setParameter("salle", salle)
                .setParameter("debut", debut)
                .setParameter("fin", fin)
                .getSingleResult();
        return chevauchements == 0;
    }
}