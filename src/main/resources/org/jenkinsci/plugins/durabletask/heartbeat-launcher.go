package main

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"syscall"
	"time"
)

func checkHeartbeatErr(err error) {
	checkErr("heartbeat", err)
}
func checkLauncherErr(err error) {
	checkErr("launcher", err)
}
func checkErr(process string, err error) {
	if err != nil {
		errString := fmt.Sprintf("(%v) %v\n", process, err.Error())
		os.Stderr.WriteString(errString)
	}
}
func launcher(wg *sync.WaitGroup, pidChan chan int, scriptPath string, logPath string, resultPath string, outputPath string) {
	defer wg.Done()
	// Note: Writing the script exit code is handled in the process command because setsid
	// applies only to the launched process and not the launcher. It is possible for the launcher
	// to get killed before it is able to write the exit code.
	// cmdString := scriptPath + "; echo $? > " + resultPath // + "; wait"
	// cmdString := scriptPath + "; wait"
	scrptCmd := exec.Command("/bin/sh", "-c", scriptPath)
	logFile, err := os.Create(logPath)
	checkLauncherErr(err)
	defer logFile.Close()

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		checkLauncherErr(err)
		defer outputFile.Close()
		scrptCmd.Stdout = outputFile
	} else {
		scrptCmd.Stdout = logFile
	}
	// capturing output
	scrptCmd.Stderr = logFile
	// tee := io.MultiWriter(os.Stdout, logFile)
	// scrptCmd.Stderr = tee
	/*
		if outputPath != "" {
			// capturing output
			teeWriter := io.MultiWriter(logFile, os.Stdout)
			scrptCmd.Stderr = teeWriter //logFile
		} else {
			scrptCmd.Stderr = os.Stdout
		}
	*/
	scrptCmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	scrptCmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	for i := 0; i < len(scrptCmd.Args); i++ {
		fmt.Printf("launcher args %v: %v\n", i, scrptCmd.Args[i])
	}
	scrptCmd.Start()
	fmt.Printf("launcher: my pid (%v), launcher pid (%v)\n", os.Getpid(), scrptCmd.Process.Pid)
	pidChan <- scrptCmd.Process.Pid
	// If we do not call wait here, the forked process will be zombied until the main program exits
	err = scrptCmd.Wait()
	checkLauncherErr(err)
	// /*
	resultVal := scrptCmd.ProcessState.ExitCode()
	fmt.Printf("launcher script exit code: %v\n", resultVal)
	// result, err := os.OpenFile(resultFile, os.O_WRONLY, 0644)
	fmt.Println("launcher writing result")
	resultFile, err := os.Create(resultPath)
	checkLauncherErr(err)
	defer resultFile.Close()
	resultFile.WriteString(strconv.Itoa(resultVal))
	checkLauncherErr(err)
	fmt.Println("about to close result file")
	err = resultFile.Close()
	checkLauncherErr(err)
	// */
	fmt.Println("launcher: done")
}

func heartbeat(wg *sync.WaitGroup, pidChan chan int, controlDir string, resultPath string, logPath string) {
	defer wg.Done()
	scriptPid := <-pidChan
	for {
		// send signal 0 because FindProcess will always return true for Unix
		err := syscall.Kill(scriptPid, syscall.Signal(0))
		fmt.Printf("heartbeat: process.Signal on pid %d returned: %v\n", scriptPid, err)
		if err != nil {
			break
		}
		_, err = os.Stat(controlDir)
		if os.IsNotExist(err) {
			break
		}
		_, err = os.Stat(resultPath)
		if !os.IsNotExist(err) {
			break
		}
		// heartbeat
		err = os.Chtimes(logPath, time.Now(), time.Now())
		checkHeartbeatErr(err)
		time.Sleep(time.Second * 3)
	}
}

func main() {
	fmt.Printf("number of args: %v\n", os.Args)
	for i := 0; i < len(os.Args); i++ {
		fmt.Printf("arg %v: %v\n", i, os.Args[i])
	}
	argLen := len(os.Args)
	if argLen < 5 || argLen > 6 {
		// invalid number of args
		os.Stderr.WriteString("Input error: expected number of args is 5 or 6 (controlDir, resultPath, logPath, scriptPath, outputPath[opt])")
		return
	}
	controlDir := os.Args[1]
	resultPath := os.Args[2]
	logPath := os.Args[3]
	scriptPath := os.Args[4]
	outputPath := ""
	if argLen == 6 {
		outputPath = os.Args[5]
	}

	var wg sync.WaitGroup
	pidChan := make(chan int)
	wg.Add(2)
	go launcher(&wg, pidChan, scriptPath, logPath, resultPath, outputPath)
	go heartbeat(&wg, pidChan, controlDir, resultPath, logPath)
	// scriptPid := <-pidChan
	/*
		for {
			// send signal 0 because FindProcess will always return true for Unix
			err := syscall.Kill(scriptPid, syscall.Signal(0))
			fmt.Printf("process.Signal on pid %d returned: %v\n", scriptPid, err)
			if err != nil {
				break
			}
			_, err = os.Stat(controlDir)
			if os.IsNotExist(err) {
				break
			}
			_, err = os.Stat(resultPath)
			if !os.IsNotExist(err) {
				break
			}
			// heartbeat
			err = os.Chtimes(logPath, time.Now(), time.Now())
			if err != nil {
				fmt.Printf("Chtimes error: %d", err)
			}
			time.Sleep(time.Second * 3)
		}
	*/
	wg.Wait()
	fmt.Println("main: done.")
}
