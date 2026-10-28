spell CoreConditional {
  on cast {
    let selected = target.nearest(24);
    if (selected.is_some) {
      let enemy = selected.value;
      if (enemy.hp_ratio < 0.3) {
        combat.damage(enemy, 40);
      } else {
        combat.damage(enemy, 12);
      }
      return true;
    }
    return false;
  }
}
