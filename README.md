# SellChest (Paper 26.2 / Java 25)

Coffre qui revend automatiquement son contenu au prix de NexShop et crédite (via Vault)
le joueur qui l'a posé.

## Compilation
Prérequis : JDK 25 + Gradle 9+.
    gradle build
Le jar est dans build/libs/SellChest-1.0.0.jar -> à mettre dans /plugins.

## Fonctionnement
- /sellchest give [joueur] [quantité]  (sellchest.admin) : donne des SellChests
- /sellchest reload                    : recharge config + prix NexShop
- Le propriétaire est enregistré dans le coffre (PDC) : survit aux redémarrages.
- Items vendables = ceux de NexShop avec un "sell-price" > 0 (lus dans plugins/NexShop/categories/*/*.yml).
- Items non vendables : restent dans le coffre.
- Double coffre impossible (SellChest <-> coffre normal, dans les deux sens).
- Vente automatique : tous les SellChests chargés sont vérifiés en continu (config `auto-sell`), sans ouvrir le coffre.
- Entonnoirs : l'item entre puis est vendu au tick suivant.

## Ajouter le SellChest au shop NexShop (achat par commande, comme les spawners)
    sellchest:
      material: CHEST
      name: "&6&lᴄᴏғғʀᴇ ᴅᴇ ᴠᴇɴᴛᴇ"
      buy-price: 5000
      commands:
        - "sellchest give %player% 1"
      commands-per-unit: true
