workspace "Smart Financial Wellness Advisor" "Architecture executable du worktree - 13 octobre 2026. Banque synthetique, IA optionnelle, aucune revendication de production bancaire." {
    !impliedRelationships false

    model {
        user = person "Utilisateur" "Gere ses finances, valide les suggestions et consulte les estimations."
        admin = person "Administrateur mock-bank" "Administre uniquement les clients et le ledger synthetiques."
        developer = person "Developpeur / stagiaire" "Prepare, evalue et exporte les modeles hors ligne."

        app = softwareSystem "Smart Financial Wellness Advisor" "Assistant financier personnel : donnees reelles locales, budgets, objectifs, previsions et conseils informatifs." {
            frontend = container "Frontend principal" "SPA francaise. Aucun appel direct a la banque, FastAPI ou aux bases." "React 19 / Vite / JavaScript" {
                routes = component "Routes et AppShell" "Navigation, pages publiques et routes protegees." "App.jsx / AppShell.jsx"
                session = component "Session et protection" "JWT en memoire, renouvellement via cookie HttpOnly, aucun jeton en localStorage." "AuthContext / ProtectedRoute"
                financialState = component "Donnees financieres partagees" "Comptes, historique, dashboard et invalidation apres mutation." "FinancialDataContext"
                dataPages = component "Pages de donnees" "Dashboard, Transactions, Categories et connexion bancaire. Montants autoritatifs fournis par le serveur." "React"
                planningPages = component "Pages de planification" "Salary Planning, Goals et Forecast. Simulations temporaires et protection des reponses obsoletes." "React"
                insightsUi = component "Score et recommandations" "Facteurs, priorites, feedback et explication locale optionnelle." "RecommendationsSection / DashboardPage"
                importsUi = component "Import CSV et ticket" "Preview/mapping/confirmation ; extraction experimentale, revue humaine puis export CSV." "CsvImportPanel / ReceiptPanel"
                chart = component "Graphique de prevision" "Solde observe et estimation de fin de mois ; liaison indicative, pas une prediction quotidienne." "ForecastBalanceChart"
                apiClient = component "Client API authentifie" "Client partage, erreurs JSON, refresh single-flight et wrappers par fonctionnalite." "services/apiClient.js"
            }
            backend = container "Backend principal" "Monolithe modulaire ; principal et statut PostgreSQL, ownership, monnaies isolees et calculs decimaux." "Java 21 / Spring Boot 4.1 / Maven" {
                security = component "Securite et erreurs" "JWT et statuts relus en base ; ACTIVE requis pour les API financieres ; erreurs JSON et validation." "SecurityConfiguration / StatusAwareAuthorizationFilter"
                auth = component "Authentification" "BCrypt, verification/reset, refresh hashes SHA-256, rotation et detection de reuse." "AuthService / AccountActionService / RefreshTokenService"
                profile = component "Utilisateur et preferences" "Profil issu du principal, email en lecture seule, preferences financieres et interface separees." "profile / user"
                email = component "Email apres commit" "Dispatch apres commit ; echec SMTP sans rollback du compte ; diagnostics sans secrets." "AfterCommitEmailDispatcher"
                audit = component "Audit" "Evenements sensibles, journal append-only et details minimises." "AuditService"
                bank = component "Connexion et synchronisation" "Connexion utilisateur, appels avant transaction d'ecriture, upserts idempotents et snapshots de solde." "BankConnectionService / BankSynchronizationService"
                bankAdapter = component "Ports et adapter bancaire" "ReadOnlyBankDataPort et MockBankAuthenticationPort ; client HTTP borne, IDs fournisseur internes." "MockBankHttpClient"
                csv = component "Import CSV" "Preview, mappings, confirmation et idempotence fichier/ligne. Aucun import automatique depuis un ticket." "CsvImportService"
                category = component "Categories et revue" "Categorie courante, regles deterministes, memoire privee puis IA ; suggestion PENDING uniquement." "CategoryService / CategorizationReviewService / MerchantRecognitionService"
                localData = component "Comptes, transactions et depenses" "Lectures locales user/mois/devise ; debits eligibles, banque POSTED, hors transferts internes confirmes." "LocalBankDataService"
                salary = component "Salary Planning" "Revenus et allocations ; capacite peut etre negative ; metadonnees de recommandations sauvegardees ensemble." "SalaryPlanService / HistoricalSalaryRecommendationService"
                goals = component "Goals" "Progres = montant initial + contributions ; allocations plafonnees par MonthlyPlan ; scenarios temporaires." "GoalService / GoalAllocationService"
                forecast = component "Forecast" "Fin de mois deterministe ; residu Huber TND optionnel ; runs persistants et simulations temporaires." "ForecastService / DeterministicForecastCalculator"
                score = component "Score financier" "Snapshots immuables 0-100, facteurs disponibles, provenance et fingerprint ; aucun LLM." "FinancialScoreService / FinancialWellnessScoreCalculator"
                recommendations = component "Recommandations" "Regles versionnees sur facteurs immuables, justifications, idempotence et feedback. Aucune action automatique." "RecommendationService / RecommendationRules"
                assistance = component "Assistance locale optionnelle" "Ownership et faits minimises ; explication avec fallback ; image ephemere validee pour extraction." "AssistanceService / AssistanceClient"
            }
            appDb = container "PostgreSQL applicatif" "Schema smart_financial_wellness ; Flyway V1-V22 du worktree ; Hibernate validate. Plans, contributions, transactions, snapshots et audit." "PostgreSQL 17" "Database"
            ai = container "Service IA" "Inference uniquement ; contrats stricts, budgets bornes et fallback. Aucun acces PostgreSQL." "Python 3.12 / FastAPI / scikit-learn" {
                aiApi = component "API et contrats" "Forecast, merchant-recognition, explications et tickets ; Pydantic strict et versions de contrat." "main.py / contracts.py / merchant_contracts.py"
                forecastRuntime = component "Runtime Forecast" "Charge le Huber fige, verifie artefact/versions et predit le residu net TND." "runtime.py"
                merchantRuntime = component "Runtime marchand" "Aliases/catalogue et modele fige, puis enrichissement optionnel ; aucun choix applique a une transaction." "merchant_runtime.py"
                enrichment = component "Enrichissement marchand borne" "Candidats comme indices, evidence non fiable, categorie enum et SUGGEST/ABSTAIN strict ; provenance HTTPS." "merchant_enrichment.py"
                providers = component "Providers Ollama" "Embeddings, raisonnement JSON strict et recherche web optionnelle ; aucune journalisation de prompts/secrets." "ollama_providers.py"
                localAssistance = component "Explication et vision" "Francais controle sans faits inventes ; JPEG/PNG ephemere nettoye, champs decimaux revus par l'utilisateur." "assistance.py"
                admission = component "Admission locale" "Slot d'inference partage par processus, delais bornes et reponses busy/fallback sures." "inference_control.py"
            }
            models = container "Artefacts figes et metadonnees" "Modeles Forecast Huber synthetique TND et marchand, catalogue, versions et empreintes ; inclus dans l'image ai-service." "joblib / JSON" "FileStore"
            pipeline = container "Pipeline ML hors ligne" "Scripts et notebooks, jamais entraine a la requete et hors Docker Compose." "Python / scikit-learn / notebooks" {
                generate = component "Generation et validation" "Donnees synthetiques reproductibles, dates et schemas controles." "synthetic_data.py / validation.py"
                features = component "Dataset et features" "Target residuel, preparation et matrice de features versionnees." "dataset_builder.py / feature_matrix.py"
                experiments = component "Entrainement et evaluation Forecast" "Splits temporels, baselines, Huber et test conserve ; seeds/configuration et resultats." "temporal_split.py / modeling.py / evaluation.py"
                merchantTraining = component "Preparation et modele marchand" "Catalogue, normalisation et export reproductible du modele de reconnaissance." "merchant_ml / export_merchant_model.py"
                export = component "Export controle" "Promotion explicite des artefacts/metadonnees vers ai-service/models avant build ; pas de deploiement automatique." "artifact.py / export_frozen_model.py"
            }
        }

        mock = softwareSystem "Mock bank" "Fournisseur bancaire externe simule ; pas de logique de bien-etre financier." {
            mockUi = container "Console mock-bank-ui" "Application d'administration independante du frontend financier." "React / Vite / JavaScript"
            mockApi = container "API mock-bank" "Comptes, soldes, transactions et administration synthetiques ; JWT client/admin separes." "Java 21 / Spring Boot 4.1" {
                mockSecurity = component "Securite fournisseur" "JWT, scopes client/admin et CORS admin limite ; aucun flux OAuth/PKCE actif." "MockBankSecurityConfiguration / MockBankJwtService"
                customerAuth = component "Login client" "Credentials synthetiques BCrypt, JWT de lecture et identite fournisseur." "MockBankAuthenticationService"
                adminAuth = component "Login administrateur" "Authentification separee pour mutations de fixtures/ledger." "AdminAuthenticationService"
                bankReads = component "Lectures de donnees" "Accounts, balance et transactions paginees ; DTOs seulement ; autre client = 404 Account not found." "AccountQueryService / BalanceQueryService / TransactionQueryService"
                lookup = component "Ownership client/compte" "Verification partagee du client et du compte ; aucune revelation inter-clients." "CustomerLookupService"
                management = component "Administration synthetique" "Gestion de clients, comptes et transactions selon les regles du ledger mock." "AdminManagementService"
                demo = component "Fixture superviseur" "Idempotente, identite fixe ; profil demo ET flag explicite ET administrateur. Desactivee par defaut." "SupervisorDemoFixtureService / Controller" "Optional"
            }
            mockDb = container "PostgreSQL mock-bank" "Schema mock_bank, Flyway V1-V4 et ledger synthetique ; volume distinct." "PostgreSQL 17" "Database"
        }
        mail = softwareSystem "Mailpit local" "Capture SMTP des emails d'actions de compte en developpement." "External"
        ollama = softwareSystem "Ollama local" "Embeddings, raisonnement et vision sur le poste ; modeles configurables ; hors Compose." "External,Optional"
        web = softwareSystem "Ollama Web Search" "Recherche publique HTTPS optionnelle pour marchands seulement ; aucun appel web pour explications/tickets." "External,Optional"

        user -> app "Consulte et gere ses finances"
        admin -> mock "Administre la banque synthetique"
        developer -> app "Prepare les modeles et la demonstration"
        app -> mock "Authentifie le client et lit ses donnees" "HTTP / JWT fournisseur"
        app -> mail "Emails apres commit" "SMTP"
        app -> ollama "Inference locale optionnelle" "HTTP" "Optional"
        app -> web "Evidence marchande publique optionnelle" "HTTPS" "Optional"
        user -> frontend "Utilise les pages" "Navigateur"
        admin -> mockUi "Utilise la console" "Navigateur"
        developer -> pipeline "Execute notebooks et scripts hors ligne"
        frontend -> backend "APIs protegees et authentification" "HTTP / JSON / JWT"
        backend -> appDb "Lit/ecrit ses propres donnees ; migrations et validation" "JPA / JDBC"
        backend -> mockApi "Login synthetique, comptes, soldes et transactions" "HTTP / JSON / JWT"
        backend -> ai "Features agregees, descripteurs assainis et assistance optionnelle" "HTTP / JSON" "Optional"
        backend -> mail "Verification et reset apres commit" "SMTP"
        mockUi -> mockApi "APIs admin avec JWT admin" "HTTP / JSON"
        mockApi -> mockDb "Son ledger et ses identites uniquement" "JPA / JDBC"
        ai -> models "Charge les artefacts figes" "Fichiers locaux"
        ai -> ollama "Embeddings, raisonnement ou vision bornes" "HTTP" "Optional"
        ai -> web "Recherche marchande optionnelle" "HTTPS" "Optional"
        pipeline -> models "Export explicite avant build de l'image IA" "Fichiers / hors ligne"

        routes -> session "Protege les routes"
        routes -> dataPages "Affiche les pages de donnees"
        routes -> planningPages "Affiche les pages de planification"
        dataPages -> financialState "Consomme / invalide les donnees"
        financialState -> apiClient "Recharge les donnees serveur"
        session -> apiClient "Login, me, refresh et logout"
        dataPages -> apiClient "Categories et synchronisation manuelle"
        planningPages -> apiClient "Plans, objectifs et previsions"
        planningPages -> chart "Affiche les valeurs de prevision du serveur"
        dataPages -> insightsUi "Affiche score et priorites"
        dataPages -> importsUi "Ouvre import ou extraction revue"
        insightsUi -> apiClient "Score, recommandations et explications"
        importsUi -> apiClient "Import et extraction, sans creation automatique"
        apiClient -> backend "Seul serveur appele par la SPA principale" "HTTP / JSON"

        frontend -> security "Requetes filtrees avant les controleurs" "HTTP / JWT"
        security -> appDb "Relit identite et statuts pendant la requete" "JPA"
        security -> auth "Autorise les routes d'authentification"
        auth -> appDb "Credentials hashees, sessions et action tokens" "JPA"
        auth -> email "Programme un dispatch apres commit"
        auth -> audit "Evenements d'authentification sans secrets"
        profile -> appDb "Profil et preferences du principal" "JPA"
        email -> mail "Envoie apres commit ; echec sans rollback" "SMTP"
        audit -> appDb "Ajoute une entree append-only" "JPA"
        bank -> bankAdapter "Utilise les ports internes ; aucun customerId client arbitraire"
        bankAdapter -> mockApi "HTTP hors transaction de persistence" "HTTP / JSON / JWT"
        bank -> appDb "Connexions, upsert et snapshots de solde" "JPA / JDBC"
        bank -> audit "Trace connexion et synchronisation"
        csv -> appDb "Source file, mappings et transactions idempotentes" "JPA"
        category -> appDb "Categorie actuelle, memoire et suggestions revues" "JPA / JDBC"
        category -> ai "Cle opaque, texte assaini, direction/devise/versions ; pas de montant ni ID SQL" "HTTP / JSON" "Optional"
        localData -> appDb "Agrege category_id courant ; user/mois/devise isoles" "SQL"
        salary -> appDb "Sauve plans, revenus, budgets et metadonnees ensemble" "JPA / SQL"
        goals -> appDb "Lit MonthlyPlan ; allocations <= max(capacite,0) ; contributions separees" "JPA"
        forecast -> appDb "Transactions, soldes dates et runs" "JPA / SQL"
        forecast -> ai "Residu TND optionnel ; base deterministe conservee" "HTTP / JSON" "Optional"
        score -> appDb "Agrege les faits autorises et produit des snapshots immuables" "JPA / SQL"
        recommendations -> score "Obtient l'evaluation courante autoritative"
        recommendations -> appDb "Regles, justifications de facteurs et feedback" "JPA"
        assistance -> recommendations "Verifie recommendation et ownership"
        assistance -> ai "Contrat minimise ou image ephemere validee ; fallback" "HTTP / JSON" "Optional"

        backend -> aiApi "Contrats minimises et reponses validees" "HTTP / JSON"
        aiApi -> forecastRuntime "Valide le contrat Forecast"
        aiApi -> merchantRuntime "Valide le batch marchand atomique"
        aiApi -> localAssistance "Explications et tickets optionnels"
        forecastRuntime -> models "Charge Huber et metadonnees"
        merchantRuntime -> models "Charge catalogue et modele fige"
        merchantRuntime -> enrichment "Recours borne si reconnaissance insuffisante" "Python" "Optional"
        enrichment -> providers "Embeddings, evidence publique et raisonnement strict"
        providers -> admission "Acquiert le slot local / budget"
        providers -> ollama "api/embed et api/chat ; JSON schema et think=false" "HTTP" "Optional"
        providers -> web "api/web_search ; resultat non fiable comme instruction" "HTTPS" "Optional"
        localAssistance -> admission "Partage le slot et respecte le budget"
        localAssistance -> ollama "Explication et vision sans recherche externe" "HTTP" "Optional"

        mockUi -> mockSecurity "Presente le JWT admin"
        backend -> mockSecurity "Presente le JWT client fournisseur" "HTTP / JWT"
        mockSecurity -> customerAuth "Login client"
        mockSecurity -> adminAuth "Login admin"
        mockSecurity -> bankReads "Autorise les lectures client"
        mockSecurity -> management "Exige le JWT administrateur"
        mockSecurity -> demo "Exige administrateur, profil et flag" "Spring Security" "Optional"
        customerAuth -> mockDb "Verifie le hash et l'etat client" "JPA"
        adminAuth -> mockDb "Verifie le hash administrateur" "JPA"
        bankReads -> lookup "Verifie le proprietaire avant lecture"
        lookup -> mockDb "Charge le client/compte autorise" "JPA"
        bankReads -> mockDb "Lit comptes, soldes et ledger" "JPA"
        management -> mockDb "Mutations administratives du ledger synthetique" "JPA"
        demo -> mockDb "Reconciliation strictement demo-only" "JPA"

        developer -> experiments "Execute et compare les experiences hors ligne"
        generate -> features "Fournit les donnees validees"
        features -> experiments "Features / target sans fuite temporelle"
        experiments -> export "Modele evalue et metadonnees reproductibles"
        merchantTraining -> export "Catalogue et modele versionnes"
        export -> models "Copie explicitement vers ai-service/models"

        deploymentEnvironment "Local" {
            deploymentNode "Poste de demonstration" "Ports hote configurables ; backend actuellement 8082, defaut Compose 8080." "Windows / Docker Desktop" {
                deploymentNode "Navigateur utilisateur" "SPA executee dans le navigateur, pas dans Nginx." "Chrome / Edge / Brave" {
                    browserApp = containerInstance frontend
                }
                deploymentNode "Navigateur administrateur" "Console independante." "Navigateur" {
                    browserAdmin = containerInstance mockUi
                }
                deploymentNode "Docker Compose" "Reseau local des services ; bases et volumes independants." "Docker Linux containers" {
                    nginxApp = infrastructureNode "frontend" "Assets statiques ; hote 5173 vers port 80. Pas de proxy API." "Nginx"
                    nginxAdmin = infrastructureNode "mock-bank-ui" "Assets admin ; hote 5174 vers port 80." "Nginx"
                    deploymentNode "backend" "Port interne 8080 ; hote BACKEND_PORT (session 8082)." "JRE 21" {
                        containerInstance backend
                    }
                    deploymentNode "postgres" "Port 5432 ; volume postgres-data conserve." "PostgreSQL 17" {
                        containerInstance appDb
                    }
                    deploymentNode "mock-bank" "Port publie 8081." "JRE 21" {
                        containerInstance mockApi
                    }
                    deploymentNode "mock-bank-db" "5432 interne uniquement ; volume mock-bank-data distinct." "PostgreSQL 17" {
                        containerInstance mockDb
                    }
                    deploymentNode "ai-service" "8000 interne, pas de port hote publie ; artefacts inclus dans l'image." "Python 3.12" {
                        containerInstance ai
                        containerInstance models
                    }
                    deploymentNode "mailpit" "SMTP 1025 ; interface locale 8025." "Mailpit" {
                        softwareSystemInstance mail
                    }
                }
                deploymentNode "Ollama sur le poste" "11434 via host.docker.internal ; modeles configurables, non geres par Compose." "Ollama" {
                    softwareSystemInstance ollama
                }
                deploymentNode "Environnement ML hors ligne" "Scripts/notebooks executes explicitement, pas un service Compose." "Python / notebooks" {
                    containerInstance pipeline
                }
                browserApp -> nginxApp "Charge HTML, JS et CSS" "HTTP :5173"
                browserAdmin -> nginxAdmin "Charge les assets admin" "HTTP :5174"
            }
            deploymentNode "Internet optionnel" "Merchant enrichment uniquement ; aucun acces externe pour explications/vision." "HTTPS" {
                softwareSystemInstance web
            }
        }
    }

    views {
        systemContext app "C1-Context" "Contexte du systeme actuel, dependances optionnelles et banque synthetique." {
            include user admin developer app mock mail ollama web
            autoLayout lr
            default
        }
        container app "C2-Application" "Applications et stockages. Pipeline hors ligne ; aucune connexion IA aux bases." {
            include user admin developer frontend backend appDb ai models pipeline mockUi mockApi mockDb mail ollama web
            autoLayout lr 400 220
        }
        container mock "C2-MockBank" "Frontiere du fournisseur synthetique independant." {
            include admin mockUi mockApi mockDb backend
            autoLayout lr
        }
        component backend "C3-Backend-Data" "Composants logiques de donnees, securite et integrations ; pas des microservices." {
            include frontend security auth profile email audit bank bankAdapter csv category localData appDb mockApi ai mail
            autoLayout lr 400 220
        }
        component backend "C3-Backend-Insights" "Calculs deterministes, capacite d'epargne persistee, snapshots immuables et assistance optionnelle." {
            include frontend security salary goals forecast score recommendations assistance appDb ai
            autoLayout lr 400 220
        }
        component frontend "C3-Frontend" "SPA principale ; toutes les requetes passent par le client API authentifie." {
            include *
            autoLayout lr
        }
        component ai "C3-AI" "Inference figee et locale bornee ; web uniquement pour marchands." {
            include *
            autoLayout lr
        }
        component mockApi "C3-MockBank" "JWT distincts, ownership et donnees synthetiques ; demo conditionnelle." {
            include *
            autoLayout lr
        }
        component pipeline "C3-ML-Pipeline" "Preparation, validation, entrainement et export hors ligne separes de l'inference." {
            include *
            autoLayout lr
        }
        deployment * "Local" "Deployment-Local" "Compose local, volumes separes, SPAs dans les navigateurs et Ollama sur l'hote." {
            include *
            autoLayout lr 400 220
        }
        styles {
            element "Element" {
                color #17324D
                background #F1F5FA
                stroke #22396F
                fontSize 22
            }
            element "Person" {
                shape Person
                background #123458
                color #FFFFFF
            }
            element "Software System" {
                background #123458
                color #FFFFFF
            }
            element "Container" {
                background #22396F
                color #FFFFFF
            }
            element "Component" {
                background #E7EFF9
                color #17324D
            }
            element "External" {
                background #586779
                color #FFFFFF
            }
            element "Database" {
                shape Cylinder
            }
            element "FileStore" {
                shape Folder
            }
            element "Deployment Node" {
                background #FFFFFF
                color #17324D
                stroke #9AAFC5
            }
            relationship "Relationship" {
                color #506773
                fontSize 18
                thickness 2
            }
            relationship "Optional" {
                dashed true
                color #776931
            }
        }
    }
}
