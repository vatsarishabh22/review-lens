"""Throwaway file for testing Review Lens comments. Close the PR without merging."""


def total(prices):
    result = 0
    for p in prices:
        result += p
    return result


def average(prices):
    return total(prices) / len(prices)
