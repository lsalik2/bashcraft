# Cookbook

Ready-made chains to paste and adapt, grouped by what they're for. Like the rest of the guide, they're written for a
creative world with cheats on.

## Getting around

### Village trip

Find the nearest village and land on the ground there.

**Give it a try!**

```text
/sh locate structure #village | surface | tp @s
```

### A desert, or else badlands

Look for one biome and fall back to another; whichever is found gets piped on.

**Give it a try!**

```text
/sh (locate biome minecraft:desert || locate biome minecraft:badlands) | surface | tp @s
```

### Mark a spot and come back

Remember where you are, wander off, then return.

**Give it a try!**

```text
/sh mark_x=$(data get entity @s Pos[0]) ; mark_z=$(data get entity @s Pos[2]) ; echo marked $mark_x $mark_z
/sh tp @s ~30 ~ ~
/sh tp @s $mark_x ~ $mark_z
```

### A soft landing

Shoot up into the sky and float back down.

**Give it a try!**

```text
/sh effect give @s minecraft:slow_falling 10 && tp @s ~ ~30 ~
```

## Players

### Who's online

**Give it a try!**

```text
/sh list | each echo {value} is online
```

### How many are online

`{result}` is the previous command's number — for `/list`, how many players are online.

**Give it a try!**

```text
/sh list | echo {result} players online
```

### Everyone's level

`each` runs a command once per player, so each one can be different.

**Give it a try!**

```text
/sh list | each data get entity {value} XpLevel
```

## Time and weather

### Countdown

**Give it a try!**

```text
/sh echo 3 ; sleep 1s ; echo 2 ; sleep 1s ; echo 1 ; sleep 1s ; title @s title "Go!"
```

### Lights out

Night for five seconds, then day again.

**Give it a try!**

```text
/sh time set night ; sleep 5s ; time set day
```

### A short shower

**Give it a try!**

```text
/sh weather rain ; sleep 5s ; weather clear
```

### Remind me

A timer that runs on its own while you carry on.

**Give it a try!**

```text
/sh (sleep 5s ; title @s title "Time's up!") & echo reminder set
```

## Shortcuts

### Quick home

A command of your own that takes you home.

**Give it a try!**

```text
/sh alias home = tp @s 0 100 0
/sh home
```

### A starter kit

One word for a handful of `give`s.

**Give it a try!**

```text
/sh alias kit = 'give @s minecraft:bread 8 && give @s minecraft:torch 16 && give @s minecraft:stone_pickaxe'
/sh kit
```

## Building and bookkeeping

### A quick pillar

Stack three gold blocks beside you, stopping at the first one that can't be placed.

**Give it a try!**

```text
/sh setblock ~2 ~ ~ minecraft:gold_block && setblock ~2 ~1 ~ minecraft:gold_block && setblock ~2 ~2 ~ minecraft:gold_block
```

### Remember when you started

**Give it a try!**

```text
/sh started=$(time query gametime) ; echo started at tick $started
```

---

← [Previous: Configuration](11-configuration.md) · [Guide index](README.md) · [Next: Troubleshooting](13-troubleshooting.md) →
