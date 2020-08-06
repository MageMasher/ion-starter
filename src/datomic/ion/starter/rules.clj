(ns datomic.ion.starter.rules)

(def group-rules-v2
  "A ruleset that verifies if a `:group` entity is a descendant or ancestor of
  another `:group` entity.

  In these rules `?a` stands for the ancestor and `?d` stands for the descendant.
  Both must be `:group` entities.

  The `group-descendant` rule gets passed two `:group` entities; the first one
  is the ancestor (?a) and the second is the descendant (?d). The rule checks
  that ?d is a descendant of ?a.

  The `group-ancestor` rules gets passed two `:group` entities; the first one
  is the descendant (?d) and the second is the ancestor (?a). The rule checks
  that ?a is an ancestor of ?d.

  If you know the ancestor and want a descendant, call `group-descendant`. If
  you know the descendant and want an ancestor, call `group-ancestor`. If you
  know both and want to check whether one descends from the other, either will
  work, but `group-ancestor` will be faster.

  This ruleset is an updated version of the older `group-rules` above."
  '[[(group-descendant [?a] ?d)
     [?a :group/id ?id]
     [?d :group/id ?id]]
    [(group-descendant [?a] ?d)
     [?a :group/groups ?d]]
    [(group-descendant [?a] ?d)
     (group-descendant ?a ?step)
     [?step :group/groups ?d]]

    [(group-ancestor [?d] ?a)
     [?d :group/id ?id]
     [?a :group/id ?id]]
    [(group-ancestor [?d] ?a)
     [?a :group/groups ?d]]
    [(group-ancestor [?d] ?a)
     (group-ancestor ?d ?step)
     [?a :group/groups ?step]]])

(def permission-rules
  (into group-rules-v2
        '[[(permission-grants-contract-type ?permission ?contract-type)
           (or (and [?permission :permission/types ?contract-type]
                    (not [?contract-type :db/ident :contract-type/all]))
               (and [?permission :permission/types :contract-type/all]
                    [?contract-type :contract-type/id]))]

          [(permissions-given-group [?group] ?permission)
           (group-ancestor ?group ?root-group)
           [?permission :permission/groups ?root-group]]

          [(permission-has-contract-role [?permission])
           (or [?permission :permission/roles :role/admin]
               [?permission :permission/roles :role/editor]
               [?permission :permission/roles :role/contract-moderator])]

          [(permission-has-workflow-admin-role [?permission])
           (or [?permission :permission/roles :role/admin]
               [?permission :permission/roles :role/workflow-moderator])]

          [(permission-has-abstractor-role [?permission])
           [?permission :permission/roles ?role]
           [?role :db/ident :role/abstractor]]

          [(permission-grants-abstraction [?workflow ?permission])
           [?workflow :workflow/action ?action]
           (or (and (permission-has-abstractor-role ?permission)
                    [?action :db/ident :contract-action/abstraction])
               (and (not (permission-has-abstractor-role ?permission))
                    (not [?action :db/ident :contract-action/abstraction])))]

          [(user-is-enabled [?user])
           [(get-else $ ?user :user/disabled false) ?disabled]
           [(not ?disabled)]]]))

(def contract-rules
  (into permission-rules
        '[;; Requires that you know users. If you know both contracts and
          ;; users, may be slower than users-given-contract.
          [(contracts-given-user [?user] ?contract)
           [?user :user/permissions ?permission]
           (permission-has-contract-role ?permission)
           (permission-grants-contract-type ?permission ?contract-type)
           [?contract :contract/type ?contract-type]
           [?contract :contract/group ?group]
           ;; Performance of this query is very sensitive to clause order. It
           ;; needs `permissions-given-group` to be at the end, so that group
           ;; and permission are both already bound.
           (permissions-given-group ?group ?permission)]

          ;; The external party variant of `contracts-given-user`
          [(contracts-given-user [?user] ?contract)
           [?user :user/email ?user-email]
           [?person :person/email ?user-email]
           [?user :user/permissions ?permission]
           [?permission :permission/roles :role/external-party]
           [?contract :contract/vendor-contacts ?person]]

          [(contracts-given-user-in-org [?user ?customer-org] ?contract)
           [?user :user/permissions ?permission]
           [?permission :permission/groups ?p-group]
           (group-ancestor ?p-group ?customer-org)
           (permission-has-contract-role ?permission)
           [?contract :contract/customer-organization ?customer-org]
           [?contract :contract/type ?contract-type]
           (permission-grants-contract-type ?permission ?contract-type)
           [?contract :contract/group ?group]
           ;; Performance of this query is very sensitive to clause order. It
           ;; needs `permissions-given-group` to be at the end, so that group
           ;; and permission are both already bound.
           (permissions-given-group ?group ?permission)]

          ;; The external party variant of `contracts-given-user-in-org`
          [(contracts-given-user-in-org [?user ?customer-org] ?contract)
           [?user :user/email ?user-email]
           [?person :person/email ?user-email]
           [?user :user/permissions ?permission]
           [?permission :permission/roles :role/external-party]
           [?contract :contract/vendor-contacts ?person]
           [?contract :contract/customer-organization ?customer-org]]

          ;; Requires that you know contracts. If you know both contracts and
          ;; users, will probably be faster than contracts-given-user.
          [(users-given-contract [?contract] ?user)
           [?contract :contract/type ?contract-type]
           [?contract :contract/group ?group]
           ;; TODO: experiment with clause order
           (permission-grants-contract-type ?permission ?contract-type)
           (permission-has-contract-role ?permission)
           (permissions-given-group ?group ?permission)
           [?user :user/permissions ?permission]]

          ;; The external party variant of `users-given-contract`
          [(users-given-contract [?contract] ?user)
           [?contract :contract/vendor-contacts ?person]
           [?person :person/email ?user-email]
           [?user :user/email ?user-email]
           [?user :user/permissions ?permission]
           [?permission :permission/roles :role/external-party]]]))

(def workflow-rules
  (into permission-rules
        '[[(permissions-given-workflow [?workflow] ?permission)
           [?workflow :workflow/entity-type ?contract-type]
           [?workflow :workflow/group ?group]
           ;; TODO: experiment with which is better first type or group
           (permission-grants-contract-type ?permission ?contract-type)
           (permissions-given-group ?group ?permission)]

          [(workflows-given-permission [?permission] ?workflow)
           (permission-grants-contract-type ?permission ?contract-type)
           [?workflow :workflow/entity-type ?contract-type]
           [?workflow :workflow/group ?group]
           ;; Performance of this query is very sensitive to clause order. It
           ;; needs `permissions-given-group` to be at the end, so that group
           ;; and permission are both already bound.
           (permissions-given-group ?group ?permission)]

          [(workflows-given-participant-user [?user] ?workflow)
           [?participant :rolemap/users ?user]
           [?phase :workflow.phase/available-participants ?participant]
           [?workflow :workflow/phases ?phase]]

          [(workflows-given-admin-user [?user] ?workflow)
           [?user :user/permissions ?permission]
           (permission-has-workflow-admin-role ?permission)
           (workflows-given-permission ?permission ?workflow)]

          [(participant-users-given-workflow [?workflow] ?user)
           [?workflow :workflow/phases ?phase]
           [?phase :workflow.phase/available-participants ?participant]
           [?participant :rolemap/users ?user]]

          [(admin-users-given-workflow [?workflow] ?user)
           (permissions-given-workflow ?workflow ?permission)
           (permission-has-workflow-admin-role ?permission)
           (permission-grants-abstraction ?workflow ?permission)
           [?user :user/permissions ?permission]]

          ;; Requires that you know users. If you know both workflows and
          ;; users, may be slower than users-given-editable-workflow.
          [(editable-workflows-given-user [?user] ?workflow)
           (or (workflows-given-participant-user ?user ?workflow)
               (workflows-given-admin-user ?user ?workflow))]

          [(visible-workflows-given-user [?user] ?workflow)
           (or (editable-workflows-given-user ?user ?workflow)
               (timesheets-given-timesheet-assistant ?user ?workflow)
               [?workflow :workflow/initiator ?user])]

          ;; users with `role/timesheet.assistant` may view all timesheets to
          ;; which they have been added in the capacity of
          ;; `role/timesheet.assistant`
          [(timesheets-given-timesheet-assistant [?user] ?workflow)
           [?user             :user/permissions                  ?permission]
           [?permission       :permission/roles                  :role/timesheet.assistant]
           [?workflow         :workflow/template                 ?timesheet-config]
           [?timesheet-config :timesheet-config/participants     ?participant]
           [?participant      :timesheet-config.participant/user ?user]
           [?participant      :timesheet-config.participant/role :role/timesheet.assistant]]

          ;; Requires that you know workflows. If you know both workflows and
          ;; users, will probably be faster than editable-workflows-given-user.
          [(users-given-editable-workflow [?workflow] ?user)
           (or (participant-users-given-workflow ?workflow ?user)
               (admin-users-given-workflow ?workflow ?user))]

          [(users-given-visible-workflow [?workflow] ?user)
           (or (users-given-editable-workflow ?workflow ?user)
               [?workflow :workflow/initiator ?user])]]))