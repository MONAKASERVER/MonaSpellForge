effect fn hunt_nearby() -> Int uses [COMBAT_DAMAGE] {
  let enemies = mobs.hostile(radius: 12b, limit: 6);

  for enemy in enemies {
    if (combat.can_attack(enemy)) {
      if (enemy.hp_ratio <= 0.25) {
        combat.damage(enemy, 30);
      } else {
        combat.damage(enemy, 10);
      }
    }
  }

  return enemies.count;
}

spell AutoHunter {
  on cast {
    return hunt_nearby();
  }

  on pulse(20t) {
    return hunt_nearby();
  }
}
