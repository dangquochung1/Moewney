NASHA'S DARK DUNGEONS: FREE HALLOWEEN ICONS
===========================================

Hi, and happy Halloween from the dungeon! This pack is free.

Inside you'll find 299 pixel art inventory icons of Halloween loot -
candy and treats, potions and witch ingredients, cursed trinkets,
weapons, masks and costume gear - and 56 rarity frames and slot
backgrounds to put behind and around them.

Every icon shows its RARITY in the way it is drawn: plain and homemade
for common, silver foil and ribbons for uncommon, bright and polished
for rare, dark violet with bats and moons for epic, rich gold for
legendary, and dark purple with spirals and moons for mystic.

Every icon is drawn front-on and sits centred on the same 48x48 canvas,
so it drops straight into an inventory slot. Nothing carries letters or
numbers, so the icons fit a game in any language.


WHERE THINGS ARE
----------------

sprites/        One 48x48 PNG per icon, sorted into folders:
                  halloween_candy_and_treats        100  candy, cakes,
                                                         cookies, pies
                  potions_and_witch_ingredients      99  potions, jars,
                                                         charms, grimoires
                  weapons_masks_and_costume_gear    100  scythes, bows,
                                                         masks, hats, capes
                Every icon starts with its rarity, e.g.
                legendary_golden_candy_corn.png and rare_bottled_ghost.png.

rarity_frames/  Separate layers - put them behind or on top of any icon:
  48px/, 64px/  slot_background_<rarity>.png   a dark slot tinted in the
                                               rarity colour
                frame_<rarity>_style1.png     simple frame
                frame_<rarity>_style2.png     cut corners with corner gems
                frame_<rarity>_style3.png     double frame with studs
                7 rarities: common (grey), uncommon (green), rare (blue),
                epic (purple), legendary (orange), mystic (magenta) and
                holy (gold) - the same frames as in the Gear Icons packs,
                so all Dark Dungeons icons look alike in one inventory.

spritesheets/   Each folder on a single sheet with equal-sized cells, handy
                if your engine likes to slice a sheet. The .json next to
                each sheet tells you which cell is which icon.

manifest.json   A list of every icon with its category and size, in case
                you want to load things by name in code.


A FEW TIPS
----------

Draw a slot like this: slot background, then the icon, then the frame
on top. The icons are small on purpose; scale them by whole numbers -
2x, 3x, 4x - and turn off smoothing so the pixels stay crisp:

  Godot       set the texture Filter to Off (or Nearest)
  Unity       Filter Mode: Point, Compression: None
  GameMaker   untick "Interpolate colours between pixels"


USING THE PACK
--------------

You're welcome to use these icons in your free and commercial games and
projects, and to edit, recolour and mix them however you like. A credit
is always lovely, but not required.

Please don't resell or share the icons themselves (as they are or
modified), and don't use them to train AI models. The full terms are in
LICENSE.txt.


Trick or treat!
- Nasha
