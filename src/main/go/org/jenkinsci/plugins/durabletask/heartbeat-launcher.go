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

// Note: EXIT signal is needed as some shell variants require an EXIT trap after catching a signal
const trapSig = "trap ':' INT TERM EXIT"
const base = "main"
const hb = "heartbeat"
const launch = "launcher"

var debugLogger *log.Logger

// func checkHeartbeatErr(output io.Writer, err error) {
// 	checkErr("heartbeat", err)
// }

// func checkLauncherErr(err error) {
// 	checkErr("launcher", err)
// }

func checkIfErr(process string, err error) bool {
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v: %v\n", strings.ToUpper(process), err.Error())
		return true
	}
	return false
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
func signalCatcher(sigChan chan os.Signal, logger *log.Logger) {
	for sig := range sigChan {
		logger.Printf("(sig catcher) caught: %v\n", sig)
		// fmt.Printf("(sig catcher) caught: %v\n", sig)
	}
}

func launcher(wg *sync.WaitGroup, exitChan chan bool, cookieName string, cookieVal string,
	interpreter string, scriptPath string, logPath string, resultPath string, outputPath string) {

	defer wg.Done()
	defer signalFinished(exitChan)
	logFile, err := os.Create(logPath)
	if checkIfErr("launcher", err) {
		exitLauncher(exitChan, -1, resultPath, logFile)
		return
	}
	defer logFile.Close()

	var scriptCmd *exec.Cmd
	if interpreter != "" {
		scriptCmd = exec.Command(interpreter, "-xe", scriptPath)
	} else {
		scriptCmd = exec.Command(scriptPath)
	}
	// jscString := fmt.Sprintf("jsc=%v", cookieVal)
	cookieString := fmt.Sprintf("%v=%v", cookieName, cookieVal)
	scriptCmd.Env = append(os.Environ(),
		// jscString,
		cookieString)

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		logIfErr(logFile, err)
		defer outputFile.Close()
		scriptCmd.Stdout = outputFile
	} else {
		scriptCmd.Stdout = logFile
	}
	if outputPath != "" {
		// capturing output
		scriptCmd.Stderr = logFile
	} else {
		// Note: pointing to os.Stdout will not capture all err logs and fail unit tests
		scriptCmd.Stderr = scriptCmd.Stdout
	}
	// Create new session
	scriptCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	for i := 0; i < len(scriptCmd.Args); i++ {
		debugLogger.Printf("(launcher) args %v: %v\n", i, scriptCmd.Args[i])
		// fmt.Fprintf(logFile, "(launcher) args %v: %v\n", i, scriptCmd.Args[i])
	}
	err = scriptCmd.Start()
	if !logIfErr(logFile, err) {
		pid := scriptCmd.Process.Pid
		debugLogger.Printf("(launcher) launched %v\n", pid)
	}
	err = scriptCmd.Wait()
	logIfErr(logFile, err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	debugLogger.Printf("(launcher) script exit code: %v\n", resultVal)
	// fmt.Printf("(launcher) script exit code: %v\n", resultVal)

	exitLauncher(exitChan, resultVal, resultPath, logFile)
	///////NEW FUNCTION
	// exitChan <- true
	// debugLogger.Printf("(launcher) signaled script exit\n")
	// // fmt.Fprintf(logFile, "(launcher) signaled script exit\n")
	// resultFile, err := os.Create(resultPath)
	// logCheckErr(debugLogger, err)
	// defer resultFile.Close()
	// resultFile.WriteString(strconv.Itoa(resultVal))
	// logCheckErr(debugLogger, err)
	// err = resultFile.Close()
	// logCheckErr(debugLogger, err)
	// fmt.Fprintln(logFile, "(launcher) done")
	// debugLogger.Println("done")
}

func signalFinished(exitChan chan bool) {
	exitChan <- true
}

func exitLauncher(exitChan chan bool, exitCode int, resultPath string, logFile io.Writer) {
	// signal heartbeat we're done
	exitChan <- true
	debugLogger.Printf("(launcher) signaled script exit\n")
	resultFile, err := os.Create(resultPath)
	if logIfErr(logFile, err) {
		return
	}
	defer resultFile.Close()
	_, err = resultFile.WriteString(strconv.Itoa(exitCode))
	logIfErr(logFile, err)
	err = resultFile.Close()
	logIfErr(logFile, err)
	debugLogger.Println("(launcher) done")
}

func heartbeat(wg *sync.WaitGroup, exitChan chan bool,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()
	hbDebug, hbErr := os.Create(controlDir + hb + ".log")
	loggerIfErr(debugLogger, hbErr)
	defer hbDebug.Close()
	debugPrefix := strings.ToUpper(hb) + " "
	hbLogger := log.New(hbDebug, debugPrefix, log.Ltime|log.Lshortfile)

	// const HBSCRIPT string = "heartbeat.sh"
	// fmt.Printf("(heartbeat) checking if %v is alive\n", launchedPid)
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
	// create the heartbeat script
	// heartbeat := fmt.Sprintf("#! /bin/sh\n%v; pid=\"$$\"; echo \"(heartbeat) pid: \"$pid\"\"; while true ; do kill -0 %v; status=\"$?\"; if [ \"$status\" -ne 0 ]; then break; fi; echo \"\"$pid\"\"; touch %v; sleep 3; done; echo \"(\\\"$pid\\\") exiting\"; exit",
	// 	trapSig, launchedPid, logPath)
	// heartbeatPath := controlDir + HBSCRIPT
	// heartbeatScript, err := os.Create(heartbeatPath)
	// checkHeartbeatErr(err)
	// err = os.Chmod(heartbeatPath, 0755)
	// checkHeartbeatErr(err)
	// heartbeatScript.WriteString(heartbeat)
	// heartbeatScript.Close()

	// heartbeatCmd := exec.Command(heartbeatPath)

	/************************************
	// Warning: DO NOT set cmd.Stdout/StdErr is set to os.Stdout/Stderr
	// If you do, the heartbeat thread will not survive jenkins termination
	///////////// DO NOT DO THIS ///////////////
	// heartbeatCmd.Stdout = os.Stdout
	// heartbeatCmd.Stderr = os.Stderr
	************************************************/
	// heartbeatCmd.Stdout = logFile
	// heartbeatCmd.Stderr = logFile
	// heartbeatCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	// heartbeatCmd.Start()
	// pid := heartbeatCmd.Process.Pid
	// fmt.Printf("(heartbeat) heartbeat pid (%v)\n", pid)
	// err = heartbeatCmd.Wait()
	// checkHeartbeatErr(err)

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
	// hbLogger.Println("exit")
}

func main() {
	// argLen := len(os.Args)
	// if argLen < 5 || argLen > 6 {
	// 	// invalid number of args
	// 	os.Stderr.WriteString("Input error: expected number of args is 5 or 6 (controlDir, resultPath, logPath, scriptString, outputPath[opt])")
	// 	return
	// }
	// controlDir := os.Args[1]
	// resultPath := os.Args[2]
	// logPath := os.Args[3]
	// scriptString := os.Args[4]
	// outputPath := ""
	// if argLen == 6 {
	// 	outputPath = os.Args[5]
	// }
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

	debugFile, debugErr := os.Create(controlDir + base + ".log")
	if debugErr != nil {
		// os.Stderr.WriteString("unable to create debug log file\n")
		return
	}
	defer debugFile.Close()
	debugPrefix := strings.ToUpper(base) + " "
	debugLogger = log.New(debugFile, debugPrefix, log.Ltime|log.Lshortfile)

	// Validate that the required flags were all command-line defined
	required := []string{controlFlag, resultFlag, logFlag, cookieNameFlag, cookieValFlag, scriptFlag}
	defined := make(map[string]bool)
	flag.Visit(func(f *flag.Flag) {
		debugLogger.Println(f.Name + ": " + f.Value.String())
		defined[f.Name] = true
	})
	for _, reqFlag := range required {
		if !defined[reqFlag] {
			errMsg := fmt.Sprintf("-%v flag missing\n", reqFlag)
			os.Stderr.WriteString(errMsg)
			return
		}
	}

	// debugLogger.Printf("number of args: %v\n", os.Args)
	// for i := 0; i < len(os.Args); i++ {
	// 	debugLogger.Printf("arg %v: %v\n", i, os.Args[i])
	// }

	ppid := os.Getppid()
	debugLogger.Printf("Parent pid is: %v\n", ppid)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, unix.SIGINT, unix.SIGTERM, unix.SIGHUP, unix.SIGQUIT)
	go signalCatcher(sigChan, debugLogger)

	exitChan := make(chan bool)
	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, exitChan, cookieName, cookieVal, interpreter, scriptPath, logPath, resultPath, outputPath)
	go heartbeat(&wg, exitChan, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	debugLogger.Println("done.")
}
