# saferis

The name is derived from 'safe' and 'eris' (Greek for 'strife' or 'discord') saferis mitigates the discord of unsafe SQL.

A type safe SQL client and interpolator for Scala 3 and ZIO

Inspired by Magnum - but opinionated toward ZIO 

This project started out from my own desire to better understand macros and type-classes in Scala 3. I really like Magnum, Quill, etc... but I wanted something that was designed from the start to be both type safe - and resource safe. To that end I wanted several things I was missing.

- Decoupled Reader/Writer type-classes 
- Reader type class with read as an effect
- Writer type class with write as an effect
- Scoped:
  - connections
  - queries
  - transactions
