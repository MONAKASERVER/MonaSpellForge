export interface StarterTemplate {
  label: string;
  description: string;
  source: string;
}

export function extractPairingCode(clipboard: string): string | undefined {
  const normalized = clipboard.trim();
  return /^\d{6}$/.test(normalized) ? normalized : undefined;
}

export const STARTER_TEMPLATES: readonly StarterTemplate[] = [
  {
    label: '最寄りの敵へ魔術攻撃',
    description: '12ブロック以内の敵へ8ダメージ',
    source: `spell MyFirstSpell {
  on cast {
    let selected = target.nearest(12b);
    if (selected.is_some && combat.can_attack(selected.value)) {
      combat.damage(selected.value, 8);
      return true;
    }
    return false;
  }
}
`
  },
  {
    label: '周囲の敵をまとめて攻撃',
    description: '半径10ブロック・最大4体',
    source: `spell AreaBurst {
  on cast {
    let enemies = mobs.hostile(radius: 10b, limit: 4);
    for enemy in enemies {
      if (combat.can_attack(enemy)) {
        combat.damage(enemy, 6);
      }
    }
    return enemies.count;
  }
}
`
  },
  {
    label: '空の術式から作る',
    description: '最小構成のArcSys術式',
    source: `spell NewSpell {
  on cast {
    return true;
  }
}
`
  }
];

