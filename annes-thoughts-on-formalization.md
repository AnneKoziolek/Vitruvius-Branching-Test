I think we should stay a bit closer to the implementation of the prototype. There, we merge branch A on branch B. So it is not symmetric what happens (and it is not clear what the conquences are...). 
I think in the definition of delta_merge, the base model m_base should also be taken into account, right? 

Does this formalization distinguish between user-intended changes (the one the users actually did through the views) and derived changes (the changes the CPRS created to stay consistent on each branch? I think we have to extend the formalization to distinguish the two. Because on afer merging, we want to replay mainly the user intended changes. The deried changes are assumed o not encode exolicit user decisions so we can freely overwrite them. 

Most recent discussion: https://chatgpt.com/g/g-p-68ea67023b30819182c27c80e5d09641/c/69c004cc-8a24-838c-b11d-d1ddaa592efe