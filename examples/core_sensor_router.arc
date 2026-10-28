spell CoreSensorRouter {
  on cast {
    let targets = mobs.hostile(radius: 12b, limit: 8);
    if (boolean_in && targets.count >= 3) {
      for enemy in targets {
        combat.damage(enemy, 6 + number_in);
      }
      return targets.count;
    }
    return 0;
  }
}
