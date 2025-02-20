package org.eqasim.core.standalone_mode_choice;


import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.analysis.DistanceUnit;
import org.eqasim.core.analysis.PersonAnalysisFilter;
import org.eqasim.core.analysis.legs.LegItem;
import org.eqasim.core.analysis.legs.LegReaderFromPopulation;
import org.eqasim.core.analysis.legs.LegWriter;
import org.eqasim.core.analysis.pt.PublicTransportLegItem;
import org.eqasim.core.analysis.pt.PublicTransportLegReaderFromPopulation;
import org.eqasim.core.analysis.pt.PublicTransportLegWriter;
import org.eqasim.core.analysis.trips.TripItem;
import org.eqasim.core.analysis.trips.TripReaderFromPopulation;
import org.eqasim.core.analysis.trips.TripWriter;
import org.eqasim.core.components.travel_time.RecordedTravelTime;
import org.eqasim.core.misc.ClassUtils;
import org.eqasim.core.misc.InjectorBuilder;
import org.eqasim.core.scenario.validation.ScenarioValidator;
import org.eqasim.core.scenario.validation.VehiclesValidator;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.core.simulation.vdf.VDFConfigGroup;
import org.eqasim.core.simulation.vdf.VDFUpdateListener;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * This class offers the functionality of running the discrete mode choice model on the whole population without having to go through the whole iterative MATSim process. It is also possible to filter-out the persons that do not have a valid alternative.
 * The class requires one parameter:
 * - config-path: a path to a MATSim config file
 * The mode choice is performed via a StandaloneModeChoice module which is configurable via a config group.
 * The StandaloneModeChoiceConfigGroup can be included in the supplied config file. If it is not provided, one with the default settings is added and these settings can be set via the commandline using the config: prefix. Below the list of supported parameters:
 * - outputDirectory: The directory in which the resulting plans will as well as the logfiles be written
 * - removePersonsWithNoValidAlternatives: if set to true, persons with no valid alternative for at least one tour or trip will be removed in the resulting population
 * More parameters can be supplied via the command line
 * - write-input-csv-trips: if specified, writes out the base trips and pt legs into a csv file called input_trips.csv and input_pt_legs.csv before performing the mode choice
 * - write-output-csv-trips: writes out the trips resulting from the mode choice, as well as pt legs, into csv files called output_trips.csv and output_pt_legs.csv in addition to the plans file
 * - travel-times-factors-path: if provided, should point out to a csv file specifying the congestion levels on the network during the day as factors by which the free speed is divided. The file in question is a csv With a header timeUpperBound;travelTimeFactor in which the timeUpperBound should be ordered incrementally.
 * - recorded-travel-times-path: mutually exclusive with the travel-times-factors-path. Points to a RecordedTravelTime file.
 * - eqasim-configurator-class: The full name of a class extending the {@link org.eqasim.core.simulation.EqasimConfigurator} class, the provided configurator class will be instantiated and used to:
 *   - Detect optional config groups using the {@link org.eqasim.core.simulation.EqasimConfigurator#addOptionalConfigGroups(Config)} method
 *   - Configure the scenario using the {@link org.eqasim.core.simulation.EqasimConfigurator#configureScenario(Scenario)} before loading
 *   - Adjust the scenario using the {@link org.eqasim.core.simulation.EqasimConfigurator#adjustScenario(Scenario)} after loading
 * - mode-choice-configurator-class: The full name of a class the extending the {@link org.eqasim.core.standalone_mode_choice.StandaloneModeChoiceConfigurator} class.
 *     Since the EqasimConfigurator objects are usually used to configure the controller with all modules necessary for a full simulation, some of these modules might cause problems during a standalone mode choice.
 *     This is why you should implement a StandaloneModeChoice configurator and override the {@link StandaloneModeChoiceConfigurator#getSpecificModeChoiceModules()} to return only the modules necessary for mode choice.
 * - simulate-after: the full name of a class that can be used to run a one-iteration simulation after the mode choice. The provided class should be be runnable (having a static main(String[] args) that expect a config-path argument as well as arguments prefixed with 'config:' that can be used to override configuration elements.
 */
public class RunStandaloneModeChoice {
    public static class TravelTimeFactors implements TravelTime {

        private final String filePath;
        private final FreeSpeedTravelTime freeSpeedTravelTime;
        private List<Double> congestionSlotUpperBounds;
        private List<Double> congestionSlotSpeedFactor;
        private static final String CSV_SEPARATOR = ";";
        private static final String TIME_UPPER_BOUND_COLUMN = "timeUpperBound";
        private static final String CONGESTION_FACTOR_COLUMN = "travelTimeFactor";

        public TravelTimeFactors(String filePath) {
            this.filePath = filePath;
            this.freeSpeedTravelTime = new FreeSpeedTravelTime();
            try {
                this.readFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void readFile() throws IOException {
            this.congestionSlotSpeedFactor = new ArrayList<>();
            this.congestionSlotUpperBounds = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(this.filePath)));
            String line;
            List<String> header = null;
            while ((line = reader.readLine()) != null) {
                List<String> row = Arrays.asList(line.split(CSV_SEPARATOR));

                if (header == null) {
                    header = row;
                } else {
                    double timeUpperBound = Double.parseDouble(row.get(header.indexOf(TIME_UPPER_BOUND_COLUMN)));
                    double speedFactor = Double.parseDouble(row.get(header.indexOf(CONGESTION_FACTOR_COLUMN)));
                    if(this.congestionSlotUpperBounds.size() > 0 && this.congestionSlotUpperBounds.get(this.congestionSlotUpperBounds.size()-1) >= timeUpperBound) {
                        throw new IllegalStateException();
                    }
                    this.congestionSlotUpperBounds.add(timeUpperBound);
                    this.congestionSlotSpeedFactor.add(speedFactor);
                }
            }
            reader.close();
        }

        @Override
        public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
            int slotIndex;
            for(slotIndex=this.congestionSlotSpeedFactor.size()-1; slotIndex>0 && congestionSlotUpperBounds.get(slotIndex)>time; slotIndex--);
            if(slotIndex < 0) {
                slotIndex=0;
            }
            return this.freeSpeedTravelTime.getLinkTravelTime(link, time, person, vehicle) * congestionSlotSpeedFactor.get(slotIndex);
        }
    }


    private static final Logger logger = LogManager.getLogger(RunStandaloneModeChoice.class);

    public static final String CMD_WRITE_INPUT_CSV = "write-input-csv-trips";
    public static final String CMD_WRITE_OUTPUT_CSV = "write-output-csv-trips";
    public static final String CMD_SIMULATE_AFTER = "simulate-after";
    public static final String CMD_CONFIG_PATH = "config-path";
    public static final String CMD_TRAVEL_TIMES_FACTORS_PATH = "travel-times-factors-path";
    public static final String CMD_RECORDED_TRAVEL_TIMES_PATH = "recorded-travel-times-path";
    public static final String CMD_SKIP_SCENARIO_CHECK = "skip-scenario-check";
    public static final String EQASIM_CONFIGURATOR_CLASS = "eqasim-configurator-class";
    public static final String MODE_CHOICE_CONFIGURATOR_CLASS = "mode-choice-configurator-class";

    public static void main(String[] args) throws CommandLine.ConfigurationException, InterruptedException, IOException {
        CommandLine cmd = new CommandLine.Builder(args) //
                .requireOptions(CMD_CONFIG_PATH)
                .allowOptions(CMD_WRITE_INPUT_CSV, CMD_WRITE_OUTPUT_CSV)
                .allowOptions(CMD_TRAVEL_TIMES_FACTORS_PATH, CMD_RECORDED_TRAVEL_TIMES_PATH)
                .allowOptions(CMD_SIMULATE_AFTER)
                .allowOptions(CMD_SKIP_SCENARIO_CHECK)
                .allowOptions(EQASIM_CONFIGURATOR_CLASS, MODE_CHOICE_CONFIGURATOR_CLASS)
                .allowAnyOption(true)
                .build();

        // Loading the config
        EqasimConfigurator configurator = cmd.hasOption(EQASIM_CONFIGURATOR_CLASS) ? ClassUtils.getInstanceOfClassExtendingOtherClass(cmd.getOptionStrict(EQASIM_CONFIGURATOR_CLASS), EqasimConfigurator.class) : new EqasimConfigurator();
        configurator.registerConfigGroup(new StandaloneModeChoiceConfigGroup(), false);

        Config config = ConfigUtils.loadConfig(cmd.getOptionStrict(CMD_CONFIG_PATH));
        configurator.updateConfig(config);
        cmd.applyConfiguration(config);
        VehiclesValidator.validate(config);

        Optional<String> travelTimesFactorsPath = cmd.getOption(CMD_TRAVEL_TIMES_FACTORS_PATH);
        Optional<String> recordedTravelTimesPath = cmd.getOption(CMD_RECORDED_TRAVEL_TIMES_PATH);


        if(travelTimesFactorsPath.isPresent() && recordedTravelTimesPath.isPresent()) {
            throw new IllegalStateException(String.format("Can't use the two options '%s' and '%s' simultaneously", CMD_TRAVEL_TIMES_FACTORS_PATH, CMD_RECORDED_TRAVEL_TIMES_PATH));
        }

        // We make sure the config is set to use DiscreteModeChoice, i.e. contains a DiscreteModeChoice module
        if(!config.getModules().containsKey("DiscreteModeChoice")) {
            throw new IllegalStateException("The config file is not set to use DiscreteModeChoice");
        }

        Scenario scenario = ScenarioUtils.createScenario(config);
        configurator.configureScenario(scenario);
        ScenarioUtils.loadScenario(scenario);

        if(!cmd.hasOption(CMD_SKIP_SCENARIO_CHECK) || !Boolean.parseBoolean(cmd.getOptionStrict(CMD_SKIP_SCENARIO_CHECK))) {
            ScenarioValidator scenarioValidator = new ScenarioValidator();
            scenarioValidator.checkScenario(scenario);
        }
        configurator.adjustScenario(scenario);

        StandaloneModeChoiceConfigurator standaloneModeChoiceConfigurator = cmd.hasOption(MODE_CHOICE_CONFIGURATOR_CLASS) ? StandaloneModeChoiceConfigurator.getSubclassInstance(cmd.getOptionStrict(MODE_CHOICE_CONFIGURATOR_CLASS), config, cmd) : new StandaloneModeChoiceConfigurator(config, cmd);

        InjectorBuilder injectorBuilder = new InjectorBuilder(scenario);

        standaloneModeChoiceConfigurator.getModeChoiceModules(config).forEach(injectorBuilder::addOverridingModule);


        travelTimesFactorsPath.ifPresent(path -> injectorBuilder.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addTravelTimeBinding("car").toInstance(new TravelTimeFactors(path));
            }
        }));

        recordedTravelTimesPath.ifPresent(path -> injectorBuilder.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addTravelTimeBinding("car").to(RecordedTravelTime.class);
            }

            @Provides
            @Singleton
            RecordedTravelTime provideRecordedTravelTime() {
                try {
                    InputStream inputStream = new FileInputStream(path);
                    RecordedTravelTime recordedTravelTime = RecordedTravelTime.readBinary(inputStream);
                    inputStream.close();
                    return recordedTravelTime;
                } catch (FileNotFoundException e) {
                	throw new IllegalStateException("Travel time input file not found: " + path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }));

        boolean usingVdfTravelTime = false;
        if(config.getModules().containsKey(VDFConfigGroup.GROUP_NAME) && VDFConfigGroup.getOrCreate(config).getInputFile() != null) {
            String usedTravelTimeArg = recordedTravelTimesPath.isPresent() ? CMD_RECORDED_TRAVEL_TIMES_PATH : travelTimesFactorsPath.isPresent() ? CMD_TRAVEL_TIMES_FACTORS_PATH : null;
            if(usedTravelTimeArg == null) {
                usingVdfTravelTime = true;
            } else {
                logger.warn(String.format("Using '%s', the input file for the '%s' config group will not be considered", usedTravelTimeArg, VDFConfigGroup.GROUP_NAME));
            }
        }

        com.google.inject.Injector injector = injectorBuilder.build();


        Population population = injector.getInstance(Population.class);
        // We initialize the TripReaderFromPopulation here as we might need it just below
        TripReaderFromPopulation tripReader = new TripReaderFromPopulation(Arrays.asList("car,pt".split(",")), injector.getInstance(PersonAnalysisFilter.class), Optional.empty(), Optional.empty());
        PublicTransportLegReaderFromPopulation ptLegReader = new PublicTransportLegReaderFromPopulation(injector.getInstance(TransitSchedule.class), injector.getInstance(PersonAnalysisFilter.class));
        LegReaderFromPopulation legReader = new LegReaderFromPopulation(Arrays.asList("car", "pt"), injector.getInstance(PersonAnalysisFilter.class), Optional.empty(), Optional.empty());
        OutputDirectoryHierarchy outputDirectoryHierarchy = injector.getInstance(OutputDirectoryHierarchy.class);

        cmd.getOption(CMD_WRITE_INPUT_CSV).ifPresent(s -> {
            if(Boolean.parseBoolean(s)) {
                writeTripsCsv(population, outputDirectoryHierarchy.getOutputFilename("input_trips.csv"), tripReader);
                writePtLegsCsv(population, outputDirectoryHierarchy.getOutputFilename("input_pt_legs.csv"), ptLegReader);
                writeLegsCsv(population, outputDirectoryHierarchy.getOutputFilename("input_legs.csv"), legReader);
            }
        });

        if(usingVdfTravelTime) {
            VDFUpdateListener vdfUpdateListener = injector.getInstance(VDFUpdateListener.class);
            vdfUpdateListener.notifyStartup(null);
        }

        StandaloneModeChoicePerformer modeChoicePerformer = injector.getInstance(StandaloneModeChoicePerformer.class);

        modeChoicePerformer.run();

        cmd.getOption(CMD_WRITE_OUTPUT_CSV).ifPresent(s -> {
            if(Boolean.parseBoolean(s)) {
                writeTripsCsv(population, outputDirectoryHierarchy.getOutputFilename("output_trips.csv"), tripReader);
                writePtLegsCsv(population, outputDirectoryHierarchy.getOutputFilename("output_pt_legs.csv"), ptLegReader);
                writeLegsCsv(population, outputDirectoryHierarchy.getOutputFilename("output_legs.csv"), legReader);
            }
        });
        if(cmd.hasOption(CMD_SIMULATE_AFTER)) {
            try {
                Class<?> runClass = Class.forName(cmd.getOptionStrict(CMD_SIMULATE_AFTER));
                Method method = runClass.getMethod("main", String[].class);

                String[] extraArgs = cmd.getAvailableOptions().stream()
                        .filter(argName -> argName.startsWith("config:"))
                        .filter(argName -> !argName.startsWith("config:standaloneModeChoice"))
                        .filter(argName -> !argName.equals("config:plans.inputPlansFile"))
                        .flatMap(argName -> Stream.of("--"+argName, cmd.getOption(argName).get()))
                        .toArray(String[]::new);

                String[] baseArgs = new String[]{
                        "--config-path", cmd.getOptionStrict(CMD_CONFIG_PATH),
                        "--config:plans.inputPlansFile", Paths.get(outputDirectoryHierarchy.getOutputFilename("output_plans.xml.gz")).toAbsolutePath().toString(),
                        "--config:controler.outputDirectory", outputDirectoryHierarchy.getOutputFilename("sim"),
                        "--config:controler.lastIteration", "0"
                };
                String[] allArgs = ArrayUtils.addAll(baseArgs, extraArgs);

                method.invoke(null, new Object[]{
                        allArgs
                });

            } catch (ClassNotFoundException | InvocationTargetException | NoSuchMethodException |
                     IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void writeTripsCsv(Population population, String filePath, TripReaderFromPopulation tripReader) {
        //We write the initial trip modes
        Collection<TripItem> trips = tripReader.readTrips(population);
        try {
            new TripWriter(trips, DistanceUnit.meter, DistanceUnit.meter).write(filePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writePtLegsCsv(Population population, String filePath, PublicTransportLegReaderFromPopulation legsReader) {
        Collection<PublicTransportLegItem> legs = legsReader.readPublicTransportLegs(population);
        try {
            new PublicTransportLegWriter(legs).write(filePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeLegsCsv(Population population, String filePath, LegReaderFromPopulation legsReader) {
        Collection<LegItem> legs = legsReader.readLegs(population);
        try {
            new LegWriter(legs, DistanceUnit.meter, DistanceUnit.meter).write(filePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
