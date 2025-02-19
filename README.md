# saferis

The name is derived from 'safe' and 'eris' (Greek for 'strife' or 'discord') 

Saferis mitigates the discord of unsafe SQL. It is a resource safe SQL client library.

A type safe SQL client and interpolator for Scala 3 and ZIO

This library was loosely inspired by Magnum (which is fantastic) - but opinionated toward ZIO and geared toward my own needs.

This project started out from my own desire to better understand metaprogramming and type classes in Scala 3.

Here are some of the goals/features that are important to me:

- The current aim is to remain database agnostic - if it supports JDBC - we want it to work.
  - I may add db specific modules at some point
- safe SQL interpolator that protects against SQL injection by building up a prepared statement.
  - interpolation results is an SqlFragment
  - supports any Writeable type class within the fragment - including other sql fragments
- decoupled Reader/Writer type-classes allows you implement only one or the other if you like.
- label based reading/writing
  - more safe than index based (can't accidentally grab a column of a compatible type and use it in the wrong class field)
  - less error prone and more flexible - the order of the columns should not need to match the order of the product fields
- Reader type class with read as an effect
- Writer type class with write as an effect
- effectual Reader/Writer types allow us to ZIO patterns for handling failures etc (like falling back to another reader/writer)
- scoped:
  - connections
  - queries
  - transactions
- guaranteed resource management for transactions and connections
- prefer layers over contextual parameters for building up services (repositories are services)
