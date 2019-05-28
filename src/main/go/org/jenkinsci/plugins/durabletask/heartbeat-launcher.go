package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

const base = "main"
const hb = "heartbeat"
const launch = "launcher"

var mainLogger *log.Logger
var launchLogger *log.Logger
var hbLogger *log.Logger

func checkIfErr(process string, err error) bool {
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v: %v\n", strings.ToUpper(process), err.Error())
		return true
	}
	return false
}

func checkLaunchErr(err error) bool {
	return loggerIfErr(launchLogger, err)
}

func loggerIfErr(logger *log.Logger, err error) bool {
	if err != nil {
		logger.Println(err.Error())
		return true
	}
	return false
}

func logIfErr(output io.Writer, err error) bool {
	if err != nil {
		fmt.Fprint(output, err.Error())
		return true
	}
	return false
}

// Catch a termination signal to allow for a graceful exit (i.e. no zombies)
func signalCatcher(sigChan chan os.Signal) {
	for sig := range sigChan {
		mainLogger.Printf("(sig catcher) caught: %v\n", sig)
	}
}

func launcher(wg *sync.WaitGroup, exitChan chan bool, cookieName string, cookieVal string,
	interpreter string, scriptPath string, resultPath string, outputPath string) {

	defer wg.Done()
	defer signalFinished(exitChan)

	var scriptCmd *exec.Cmd
	if interpreter != "" {
		scriptCmd = exec.Command(interpreter, "-xe", scriptPath)
	} else {
		scriptCmd = exec.Command(scriptPath)
	}
	cookieString := fmt.Sprintf("%v=%v", cookieName, cookieVal)
	scriptCmd.Env = append(os.Environ(),
		cookieString)

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		if checkLaunchErr(err) {
			exitLauncher(exitChan, -2, resultPath)
			return
		}
		defer outputFile.Close()
		scriptCmd.Stdout = outputFile
	} else {
		scriptCmd.Stdout = launchLogger.Writer()
	}
	if outputPath != "" {
		// capturing output
		scriptCmd.Stderr = launchLogger.Writer()
	} else {
		// Note: pointing to os.Stdout will not capture all err logs
		scriptCmd.Stderr = scriptCmd.Stdout
	}
	// Create new session
	scriptCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	for i := 0; i < len(scriptCmd.Args); i++ {
		launchLogger.Printf("args %v: %v\n", i, scriptCmd.Args[i])
	}
	err := scriptCmd.Start()
	if checkLaunchErr(err) {
		exitLauncher(exitChan, -2, resultPath)
		return
	}
	pid := scriptCmd.Process.Pid
	launchLogger.Printf("launched %v\n", pid)
	err = scriptCmd.Wait()
	checkLaunchErr(err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	launchLogger.Printf("script exit code: %v\n", resultVal)

	exitLauncher(exitChan, resultVal, resultPath)
}

func signalFinished(exitChan chan bool) {
	exitChan <- true
}

func exitLauncher(exitChan chan bool, exitCode int, resultPath string) {
	launchLogger.Printf("signaled script exit\n")
	resultFile, err := os.Create(resultPath)
	if checkLaunchErr(err) {
		return
	}
	defer resultFile.Close()
	_, err = resultFile.WriteString(strconv.Itoa(exitCode))
	checkLaunchErr(err)
	err = resultFile.Close()
	checkLaunchErr(err)
	launchLogger.Println("done")
}

func heartbeat(wg *sync.WaitGroup, exitChan chan bool,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()

	_, err := os.Stat(controlDir)
	if os.IsNotExist(err) {
		hbLogger.Printf("%v\n", err.Error())
		return
	}
	_, err = os.Stat(resultPath)
	if !os.IsNotExist(err) {
		hbLogger.Printf("Result file already exists, stopping heartbeat.\n%v\n", resultPath)
		return
	}

	for {
		select {
		case <-exitChan:
			hbLogger.Println("received script finished, exiting")
			return
		default:
			// heartbeat
			hbLogger.Println("touch log")
			err = os.Chtimes(logPath, time.Now(), time.Now())
			loggerIfErr(hbLogger, err)
			time.Sleep(time.Second * 3)
		}
	}
}

func main() {
	var controlDir, resultPath, logPath, cookieName, cookieVal, scriptPath, interpreter, outputPath string
	const controlFlag = "controldir"
	const resultFlag = "result"
	const logFlag = "log"
	const cookieNameFlag = "cookiename"
	const cookieValFlag = "cookieval"
	const scriptFlag = "script"
	const shellFlag = "shell"
	const outputFlag = "output"
	flag.StringVar(&controlDir, controlFlag, "", "working directory")
	flag.StringVar(&resultPath, resultFlag, "", "full path of the result file")
	flag.StringVar(&logPath, logFlag, "", "full path of the log file")
	flag.StringVar(&cookieName, cookieNameFlag, "", "name of the jenkins server cookie")
	flag.StringVar(&cookieVal, cookieValFlag, "", "value of the jenkins server cookie")
	flag.StringVar(&scriptPath, scriptFlag, "", "full path of the script to be launched")
	flag.StringVar(&interpreter, shellFlag, "", "(optional) interpreter to use")
	flag.StringVar(&outputPath, outputFlag, "", "(optional) if recording output, full path of the output file")
	flag.Parse()

	logFile, logErr := os.Create(logPath)
	if checkIfErr("launcher", logErr) {
		return
	}
	defer logFile.Close()
	mainLogger = log.New(logFile, strings.ToUpper(base)+" ", log.Lmicroseconds|log.Lshortfile)
	launchLogger = log.New(logFile, strings.ToUpper(launch)+" ", log.Lmicroseconds|log.Lshortfile)
	hbLogger = log.New(logFile, strings.ToUpper(hb)+" ", log.Lmicroseconds|log.Lshortfile)

	// Validate that the required flags were all command-line defined
	required := []string{controlFlag, resultFlag, logFlag, cookieNameFlag, cookieValFlag, scriptFlag}
	defined := make(map[string]bool)
	flag.Visit(func(f *flag.Flag) {
		mainLogger.Println(f.Name + ": " + f.Value.String())
		defined[f.Name] = true
	})
	for _, reqFlag := range required {
		if !defined[reqFlag] {
			errMsg := fmt.Sprintf("-%v flag missing\n", reqFlag)
			mainLogger.Println(errMsg)
			return
		}
	}

	mainLogger.Printf("Main pid is: %v\n", os.Getpid())
	mainLogger.Printf("Parent pid is: %v\n", os.Getppid())

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, unix.SIGINT, unix.SIGTERM, unix.SIGHUP)
	go signalCatcher(sigChan)

	exitChan := make(chan bool)
	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, exitChan, cookieName, cookieVal, interpreter, scriptPath, resultPath, outputPath)
	go heartbeat(&wg, exitChan, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	mainLogger.Println("done.")
}
