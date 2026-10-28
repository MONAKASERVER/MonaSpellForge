spell CoreStateMemory {
  state successful_hits = 0 persist(player, ttl: 7d);

  on cast {
    if (combat.can_attack(entity_in)) {
      successful_hits += 1;
      if (successful_hits >= 5) {
        combat.damage(entity_in, 36);
        successful_hits = 0;
      } else {
        combat.damage(entity_in, 8);
      }
    }
    return successful_hits;
  }
}
