# What is Helios?

One of the appealing things about Philips Hue is the ability to vary colour
temperature and brightness based on the time of day. The links below explain the
motivations for doing this, so I won't repeat them here:

* https://www.reddit.com/r/Hue/comments/s2e1hx/upcoming_philips_hue_natural_daylight_simulation/
* https://huehomelighting.com/philips-hue-natural-daylight-simulation/

At the time that I created Helios, and at the time that I write this, there is
no built-in way to have this happen fully automatically. Philips do sell a newer
version of the switches I have, which are stated to support something like what
I want, but the descriptions I'd seen didn't make it clear exactly what
behaviour is possible.

However, even if they did support the behaviour I wanted, I didn't like the fact
that to achieve functionality which ultimately is just a software thing I would
have to replace all the switches I already have. Aside from the money, the
bigger issue is the waste. Why should it be necessary to replace perfectly good
switches with new ones which in terms of hardware aren't significantly
different, just in order to gain access to a new software behaviour? It's a
waste of the energy and materials that go into the producing the new switches,
and of what went into producing the old switches, which it'd be unlikely I could
usefully repurpose.

So - I wanted them to behave in a way that should be entirely possible with the
existing hardware, and could see that it would be possible to achieve this using
the Hue API, and software that I could write. Hence Helios.

As the two links above indicate, it sounds like Philips are working on a feature
which could allow me to achieve what I want with the Philips software itself,
and without having to replace the switches I have, but at the time that I
created Helios, I was not aware of this. As the feature is not available at the
time that I write this, I am still refining Helios. It may, however, not have a
long life.

# See Also

* https://github.com/wpietri/sunrise
  * I discovered this pretty early on while researching and developing Helios,
    but decided to build my own solution so I could more easily achieve exactly
    what I want.
* https://github.com/stefanwichmann/kelvin
  * I discovered this some time after having got Helios to the point that it
    pretty much does what I want. It sounds like it supports very similar
    behaviour to Helios but with more polish. I would probably have tried it and
    used it instead if I'd found it before creating Helios.