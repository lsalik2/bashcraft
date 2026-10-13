# Cookbook

Ready-made chains to paste and adapt.

## Village trip

Find the nearest village and land on the ground there.

**Give it a try!**

```text
/sh locate structure #village | surface | tp @s
```

## Feed everyone

Give every online player some bread.

**Give it a try!**

```text
/sh list | each give {value} minecraft:bread 3
```

## Quick home

A command of your own that takes you home.

**Give it a try!**

```text
/sh alias home = tp @s 0 100 0
/sh home
```

## Countdown

**Give it a try!**

```text
/sh echo 3 ; sleep 1s ; echo 2 ; sleep 1s ; echo 1 ; sleep 1s ; title @s title "Go!"
```

## Lights out

Night for five seconds, then day again.

**Give it a try!**

```text
/sh time set night ; sleep 5s ; time set day
```

## Who's online

**Give it a try!**

```text
/sh list | each echo {value} is online
```

## A quick pillar

Stack three gold blocks beside you, stopping at the first one that can't be placed.

**Give it a try!**

```text
/sh setblock ~2 ~ ~ minecraft:gold_block && setblock ~2 ~1 ~ minecraft:gold_block && setblock ~2 ~2 ~ minecraft:gold_block
```

## Remember when you started

**Give it a try!**

```text
/sh started=$(time query gametime) ; echo started at tick $started
```

---

← [Previous: Configuration](11-configuration.md) · [Guide index](README.md) · [Next: Troubleshooting](13-troubleshooting.md) →
