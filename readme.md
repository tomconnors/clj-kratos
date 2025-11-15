# Clojure and Kratos

Kratos is an application that manages the authn part of your web app. This little demo app shows how to use it from a Clojure app that does all its rendering on the server.

The basic idea with Kratos is to send requests to it from your server to create "flows", which are authn-related processes such as register, login, and so on, and use the data describing those flows to render UI. Also be sure to send cookies between your client and Kratos.

Like the Kratos selfhost quickstart, this repo runs kratos and mailslurper via docker. Mailslurper is just a place to send emails to avoid sending real emails - use that to do email verification and sign in w/ codes.

You'll also see `./kratos/`. Under there we have:
- `kratos.yml`: This configures the kratos application server.
- `identity.schema.json`: This configures identity rules for your app.

ns `app.main` has all the logic for rendering forms and creating/getting flows. You'll also see that there's a post-login, pre-registration, and post-registration hook.

I used a naive flow->DOM approach, mostly just turning the data from Kratos into hiccup. This leads to conflicts in some cases based on the config in `kratos.yml` and `identity.schema.json`. I saw situations where there were mutually exclusive but required fields in forms, like "password" and "code". A smarter approach might be to write custom DOM and just scoop what you need (input names and such) from the flow's data.

# Startup

- `docker-compose up`
- `clojure -M:dev:cider`
- eval the stuff at the bottom of app.main
- optionally build css: `clj -T:build css`

# flows

## registration
with code or password


## verification


## login


## recovery

## settings

## logout

