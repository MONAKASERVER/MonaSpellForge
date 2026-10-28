spell ApocalypseEngine {
  on pulse(20t) {
    let targets = mobs.hostile(radius: 24b, limit: 8);

    for target in targets {
      if (target.hp_ratio < 0.3) {
        spell.cast("EXECUTION", target);
      } else {
        spell.cast("THUNDER", target);
      }
    }
  }
}
