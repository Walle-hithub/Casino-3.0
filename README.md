# Casino (Spigot 26.3) - maquinas fisicas con minerales reales
Requisitos: Java 25. No necesita Vault ni plugin de economia.

## Compilar
    mvn clean package        (o usa la accion de GitHub en .github/workflows)
El .jar queda en target/Casino.jar -> copialo a plugins/.

## Construir maquinas fisicas (OP / casino.admin)
Ponte en una zona plana y despejada MIRANDO hacia donde quieres construir y escribe:
    /casino construir tragaperras <cantidad> <mineral>     (necesita 3x2 bloques libres)
    /casino construir ruleta <cantidad> <mineral>          (necesita 7x7 bloques libres)
Ejemplo: /casino construir tragaperras 3 diamante

- Tragaperras: cuerpo de bloques, 3 rodillos flotantes y una PALANCA real (lado derecho). Tira de ella.
- Ruleta: mesa con 13 casillas y una bola que gira. Haz clic en el bloque ROJO, VERDE o NEGRO del borde de la mesa para apostar.
- Para quitar una maquina, rompe cualquiera de sus bloques siendo admin.
- Se guardan en plugins/Casino/machines.yml

## Carteles (opcional)
Cartel con: [Casino] / slots|moneda|dados / cantidad / mineral  -> clic derecho para jugar.

## Comandos de juego (opcionales)
/casino slots <cantidad> <mineral>
/casino coinflip <cantidad> <mineral> <cara|cruz>
/casino dados <cantidad> <mineral>
/casino reload   (casino.admin)

Minerales y limites: config.yml -> bet.items
Premios de jugadores desconectados: plugins/Casino/pending.yml
